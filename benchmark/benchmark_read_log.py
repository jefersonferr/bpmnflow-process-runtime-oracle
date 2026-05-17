"""
Read benchmark — GET /workflow/{instanceId}
===========================================
Measures the latency of the endpoint that returns the full state of an instance
(currentActivity + activityHistory + variables + conclusions).

This is exactly the endpoint compared in the paper:
  → JPA     — JPQL with JOIN FETCH (multiple SQL joins)
  → SODA    — Oracle JSON Duality View with QBE filter

Methodology:
  1. Fetches up to MAX_IDS real instanceIds from the database via GET /workflow
  2. Warms up the endpoint (WARMUP_REQUESTS requests discarded)
  3. Executes TOTAL_REQUESTS requests distributed randomly across the IDs
  4. Calculates p50, p90, p95, p99, min, max and throughput
  5. Saves results to benchmark_results_{timestamp}.json

Usage:
  pip install httpx rich numpy
  python benchmark_read_log.py

  # environment variables:
  BASE_URL=http://localhost:8080
  TOTAL_REQUESTS=1000    → total measured GETs
  WARMUP_REQUESTS=50     → warmup GETs (discarded)
  CONCURRENCY=10         → simultaneous workers
  MAX_IDS=500            → how many instanceIds to load for random sampling
  LABEL=JPA              → identifies the profile in the output JSON (JPA or DUALITY)
"""

import asyncio
import random
import os
import time
import json
from datetime import datetime
from statistics import median

import httpx
import numpy as np
from rich.console import Console
from rich.progress import (
    Progress, SpinnerColumn, BarColumn,
    TaskProgressColumn, TimeElapsedColumn, TextColumn,
)
from rich.table import Table

# ──────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────
BASE_URL         = os.getenv("BASE_URL",         "http://localhost:8080")
TOTAL_REQUESTS   = int(os.getenv("TOTAL_REQUESTS",   "1000"))
WARMUP_REQUESTS  = int(os.getenv("WARMUP_REQUESTS",  "50"))
CONCURRENCY      = int(os.getenv("CONCURRENCY",      "10"))
MAX_IDS          = int(os.getenv("MAX_IDS",          "500"))
ID_MIN           = int(os.getenv("ID_MIN",           "1"))
ID_MAX           = int(os.getenv("ID_MAX",           "10000"))
LABEL            = os.getenv("LABEL",                "JPA")   # JPA | DUALITY
TIMEOUT_SECONDS  = float(os.getenv("TIMEOUT_SECONDS", "30"))

console = Console()


# ──────────────────────────────────────────────
# 1. Collect real instanceIds from the database
# ──────────────────────────────────────────────
async def fetch_instance_ids(client: httpx.AsyncClient) -> list[int]:
    """
    Generates IDs from a sequential range (ID_MIN to ID_MAX) and randomly
    samples MAX_IDS from them.
    """
    console.print(f"  Generating IDs in range [{ID_MIN}, {ID_MAX}] and sampling {MAX_IDS} ...")
    full_range = list(range(ID_MIN, ID_MAX + 1))
    random.shuffle(full_range)
    ids = full_range[:MAX_IDS]
    console.print(f"  [green]{len(ids)}[/green] instanceIds selected "
                  f"(min={min(ids)}, max={max(ids)})\n")
    return ids


# ──────────────────────────────────────────────
# 2. Benchmark worker
# ──────────────────────────────────────────────
async def measure_request(
    client: httpx.AsyncClient,
    semaphore: asyncio.Semaphore,
    instance_id: int,
    latencies: list[float],
    errors: list[dict],
):
    async with semaphore:
        t0 = time.perf_counter()
        try:
            resp = await client.get(f"{BASE_URL}/workflow/{instance_id}")
            resp.raise_for_status()
            elapsed_ms = (time.perf_counter() - t0) * 1000
            latencies.append(elapsed_ms)
        except httpx.HTTPStatusError as exc:
            errors.append({"instanceId": instance_id, "status": exc.response.status_code,
                           "error": exc.response.text[:200]})
        except Exception as exc:
            errors.append({"instanceId": instance_id, "error": str(exc)})


# ──────────────────────────────────────────────
# 3. Run phase (warmup or measurement)
# ──────────────────────────────────────────────
async def run_phase(
    client: httpx.AsyncClient,
    ids: list[int],
    n_requests: int,
    concurrency: int,
    label: str,
    progress: Progress,
) -> tuple[list[float], list[dict]]:
    latencies: list[float] = []
    errors: list[dict] = []
    semaphore = asyncio.Semaphore(concurrency)
    task = progress.add_task(label, total=n_requests)

    async def worker(iid):
        await measure_request(client, semaphore, iid, latencies, errors)
        progress.advance(task)

    sample = [random.choice(ids) for _ in range(n_requests)]
    await asyncio.gather(*[worker(iid) for iid in sample])
    return latencies, errors


# ──────────────────────────────────────────────
# 3b. Verbose warmup with per-request logging
# ──────────────────────────────────────────────
async def run_warmup_verbose(
    client: httpx.AsyncClient,
    ids: list[int],
    n_requests: int,
    concurrency: int,
    progress: Progress,
) -> list[float]:
    """
    Runs the warmup SEQUENTIALLY (concurrency=1) for the first VERBOSE_WARMUP
    requests to log individual latencies and identify where the cost concentrates.
    The remainder runs in parallel as normal.
    """
    VERBOSE_N = int(os.getenv("VERBOSE_WARMUP", "10"))  # first N sequential requests
    latencies_verbose: list[float] = []
    latencies_rest:    list[float] = []
    errors:            list[dict]  = []

    task = progress.add_task("[yellow]Warmup (discarded)   ", total=n_requests)

    # ── Sequential phase: individual logging ──────────────────────────────
    console.print(f"\n  [yellow]Verbose warmup:[/yellow] first {min(VERBOSE_N, n_requests)} requests (sequential)")
    sample_verbose = [random.choice(ids) for _ in range(min(VERBOSE_N, n_requests))]
    for i, iid in enumerate(sample_verbose):
        t0 = time.perf_counter()
        try:
            resp = await client.get(f"{BASE_URL}/workflow/{iid}")
            resp.raise_for_status()
            ms = (time.perf_counter() - t0) * 1000
            latencies_verbose.append(ms)
            flag = "[red]SLOW[/red]" if ms > 1000 else "[green]OK[/green]"
            console.print(f"    req {i+1:02d} · id={iid:6d} · {ms:7.1f} ms · {flag}")
        except Exception as exc:
            ms = (time.perf_counter() - t0) * 1000
            console.print(f"    req {i+1:02d} · id={iid:6d} · {ms:7.1f} ms · [red]ERR: {exc}[/red]")
        progress.advance(task)

    # ── Parallel phase: remaining warmup requests ─────────────────────────
    rest = n_requests - len(sample_verbose)
    if rest > 0:
        console.print(f"  [yellow]Remaining warmup:[/yellow] {rest} requests (concurrency={concurrency})")
        sem_n = asyncio.Semaphore(concurrency)
        sample_rest = [random.choice(ids) for _ in range(rest)]

        async def worker(iid):
            async with sem_n:
                t0 = time.perf_counter()
                try:
                    resp = await client.get(f"{BASE_URL}/workflow/{iid}")
                    resp.raise_for_status()
                    latencies_rest.append((time.perf_counter() - t0) * 1000)
                except Exception:
                    pass
                progress.advance(task)

        await asyncio.gather(*[worker(iid) for iid in sample_rest])

    # ── Warmup summary ────────────────────────────────────────────────────
    all_lat = latencies_verbose + latencies_rest
    if all_lat:
        import numpy as _np
        arr = _np.array(all_lat)
        console.print(
            f"\n  [yellow]Warmup summary[/yellow] · "
            f"p50={_np.percentile(arr, 50):.0f}ms · "
            f"p90={_np.percentile(arr, 90):.0f}ms · "
            f"max={_np.max(arr):.0f}ms"
        )
    return all_lat


# ──────────────────────────────────────────────
# 4. Statistics
# ──────────────────────────────────────────────
def stats(latencies: list[float]) -> dict:
    a = np.array(latencies)
    return {
        "n":       len(a),
        "min_ms":  round(float(np.min(a)), 2),
        "p50_ms":  round(float(np.percentile(a, 50)), 2),
        "p90_ms":  round(float(np.percentile(a, 90)), 2),
        "p95_ms":  round(float(np.percentile(a, 95)), 2),
        "p99_ms":  round(float(np.percentile(a, 99)), 2),
        "max_ms":  round(float(np.max(a)), 2),
        "mean_ms": round(float(np.mean(a)), 2),
        "std_ms":  round(float(np.std(a)), 2),
    }


def print_stats_table(s: dict, label: str):
    table = Table(title=f"Results — {label}", show_header=True, header_style="bold cyan")
    table.add_column("Metric", style="bold")
    table.add_column("Value (ms)", justify="right")
    table.add_row("min",  str(s["min_ms"]))
    table.add_row("p50",  str(s["p50_ms"]))
    table.add_row("p90",  str(s["p90_ms"]))
    table.add_row("p95",  str(s["p95_ms"]))
    table.add_row("p99",  str(s["p99_ms"]))
    table.add_row("max",  str(s["max_ms"]))
    table.add_row("mean", str(s["mean_ms"]))
    table.add_row("std",  str(s["std_ms"]))
    console.print(table)


# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────
async def main():
    console.rule(f"[bold]BPMNFlow — Read benchmark ({LABEL})[/bold]")
    console.print(f"  Base URL      : [cyan]{BASE_URL}[/cyan]")
    console.print(f"  Profile       : [cyan]{LABEL}[/cyan]")
    console.print(f"  Requests      : [cyan]{WARMUP_REQUESTS}[/cyan] warmup + "
                  f"[cyan]{TOTAL_REQUESTS}[/cyan] measured")
    console.print(f"  Concurrency   : [cyan]{CONCURRENCY}[/cyan] workers\n")

    async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
        ids = await fetch_instance_ids(client)

        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(), TaskProgressColumn(), TimeElapsedColumn(),
            console=console,
        ) as progress:

            # ── Verbose warmup (discarded) ─────────────────────────────────
            console.print("  [yellow]Phase 1/2:[/yellow] warming up with verbose logging ...")
            await run_warmup_verbose(client, ids, WARMUP_REQUESTS, CONCURRENCY, progress)

            # ── Measurement ────────────────────────────────────────────────
            console.print("\n  [green]Phase 2/2:[/green] measuring ...")
            start_ts = time.perf_counter()
            latencies, errors = await run_phase(
                client, ids, TOTAL_REQUESTS, CONCURRENCY,
                "[green]GET /workflow/{id}   ", progress,
            )
            elapsed = time.perf_counter() - start_ts

    # ── Results ───────────────────────────────────────────────────────────
    console.print()
    s = stats(latencies)
    print_stats_table(s, LABEL)

    throughput = round(len(latencies) / elapsed, 1)
    error_rate = round(len(errors) / TOTAL_REQUESTS * 100, 2)
    console.print(f"\n  Throughput  : [cyan]{throughput}[/cyan] req/s")
    console.print(f"  Errors      : [{'red' if errors else 'green'}]{len(errors)}[/] "
                  f"({error_rate}%)")
    console.print(f"  Total time  : [cyan]{elapsed:.1f}s[/cyan]")

    # ── Save JSON ─────────────────────────────────────────────────────────
    result = {
        "label":       LABEL,
        "timestamp":   datetime.now().isoformat(),
        "config": {
            "base_url":        BASE_URL,
            "total_requests":  TOTAL_REQUESTS,
            "warmup_requests": WARMUP_REQUESTS,
            "concurrency":     CONCURRENCY,
            "ids_sampled":     len(ids),
        },
        "stats":       s,
        "throughput":  throughput,
        "error_count": len(errors),
        "error_rate":  error_rate,
        "errors":      errors[:20],   # first 20 to keep the file small
    }
    out_path = f"benchmark_results_{LABEL}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    console.print(f"\n  [bold]Results saved to:[/bold] [cyan]{out_path}[/cyan]")
    console.rule()
    console.print("\n[bold]Next steps:[/bold]")
    console.print(f"  1. Switch the application profile to the other mode (JPA ↔ Duality)")
    console.print(f"  2. Run again with LABEL={'DUALITY' if LABEL == 'JPA' else 'JPA'}")
    console.print(f"  3. Compare the two generated JSON files for the paper\n")


if __name__ == "__main__":
    asyncio.run(main())