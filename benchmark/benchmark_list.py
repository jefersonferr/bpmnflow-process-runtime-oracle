"""
List benchmark — GET /workflow?status=ACTIVE&page=0&size=50
===========================================================
Measures the latency of the paginated listing endpoint with status filter.

With JPA  → paginated JPQL query via PageRequest
With SODA → QBE scan on the Duality View with skip/limit

Usage:
  pip install httpx rich numpy
  python benchmark_list.py

  # environment variables:
  BASE_URL=http://localhost:8080
  STATUS=ACTIVE             → status to filter by (ACTIVE, COMPLETED, CANCELLED)
  PAGE=0                    → page number (0-based)
  SIZE=50                   → page size
  TOTAL_REQUESTS=100        → total measured GETs
  WARMUP_REQUESTS=10        → warmup GETs (discarded)
  CONCURRENCY=5             → simultaneous workers
  LABEL=JPA_UCP
"""

import asyncio
import os
import time
import json
from datetime import datetime

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
STATUS           = os.getenv("STATUS",           "ACTIVE")
PAGE             = int(os.getenv("PAGE",         "0"))
SIZE             = int(os.getenv("SIZE",         "50"))
TOTAL_REQUESTS   = int(os.getenv("TOTAL_REQUESTS",   "100"))
WARMUP_REQUESTS  = int(os.getenv("WARMUP_REQUESTS",  "10"))
CONCURRENCY      = int(os.getenv("CONCURRENCY",      "5"))
LABEL            = os.getenv("LABEL",                "JPA_UCP")
TIMEOUT_SECONDS  = float(os.getenv("TIMEOUT_SECONDS", "60"))

console = Console()


# ──────────────────────────────────────────────
# Worker
# ──────────────────────────────────────────────
async def measure_request(
    client: httpx.AsyncClient,
    semaphore: asyncio.Semaphore,
    latencies: list[float],
    errors: list[dict],
    result_sizes: list[int],
):
    async with semaphore:
        t0 = time.perf_counter()
        try:
            resp = await client.get(
                f"{BASE_URL}/workflow",
                params={"status": STATUS, "page": PAGE, "size": SIZE},
            )
            resp.raise_for_status()
            elapsed_ms = (time.perf_counter() - t0) * 1000
            latencies.append(elapsed_ms)
            try:
                result_sizes.append(len(resp.json()))
            except Exception:
                result_sizes.append(0)
        except httpx.HTTPStatusError as exc:
            errors.append({"status": exc.response.status_code, "error": exc.response.text[:200]})
        except Exception as exc:
            errors.append({"error": str(exc)})


# ──────────────────────────────────────────────
# Run phase
# ──────────────────────────────────────────────
async def run_phase(
    client: httpx.AsyncClient,
    n_requests: int,
    label: str,
    progress: Progress,
) -> tuple[list[float], list[dict], list[int]]:
    latencies:    list[float] = []
    errors:       list[dict]  = []
    result_sizes: list[int]   = []
    semaphore = asyncio.Semaphore(CONCURRENCY)
    task = progress.add_task(label, total=n_requests)

    async def worker():
        await measure_request(client, semaphore, latencies, errors, result_sizes)
        progress.advance(task)

    await asyncio.gather(*[worker() for _ in range(n_requests)])
    return latencies, errors, result_sizes


# ──────────────────────────────────────────────
# Statistics
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


def print_stats_table(s: dict, title: str):
    table = Table(title=title, show_header=True, header_style="bold cyan")
    table.add_column("Metric", style="bold")
    table.add_column("Value (ms)", justify="right")
    for k in ["min", "p50", "p90", "p95", "p99", "max", "mean", "std"]:
        table.add_row(k, str(s[f"{k}_ms"]))
    console.print(table)


# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────
async def main():
    console.rule(f"[bold]BPMNFlow — List benchmark ({LABEL})[/bold]")
    console.print(f"  Base URL      : [cyan]{BASE_URL}[/cyan]")
    console.print(f"  Profile       : [cyan]{LABEL}[/cyan]")
    console.print(f"  Endpoint      : [cyan]GET /workflow?status={STATUS}&page={PAGE}&size={SIZE}[/cyan]")
    console.print(f"  Requests      : [cyan]{WARMUP_REQUESTS}[/cyan] warmup + "
                  f"[cyan]{TOTAL_REQUESTS}[/cyan] measured")
    console.print(f"  Concurrency   : [cyan]{CONCURRENCY}[/cyan] worker(s)")
    console.print(f"  Timeout       : [cyan]{TIMEOUT_SECONDS}s[/cyan]\n")

    async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(), TaskProgressColumn(), TimeElapsedColumn(),
            console=console,
        ) as progress:

            # ── Warmup (discarded) ────────────────────────────────────────
            console.print("  [yellow]Phase 1/2:[/yellow] warming up ...")
            await run_phase(client, WARMUP_REQUESTS,
                            "[yellow]Warmup (discarded)             ", progress)

            # ── Measurement ───────────────────────────────────────────────
            console.print("\n  [green]Phase 2/2:[/green] measuring ...")
            start_ts = time.perf_counter()
            latencies, errors, sizes = await run_phase(
                client, TOTAL_REQUESTS,
                "[green]GET /workflow?status=ACTIVE    ", progress,
            )
            elapsed = time.perf_counter() - start_ts

    # ── Results ───────────────────────────────────────────────────────────
    console.print()
    if not latencies:
        console.print("[red]No successful requests. Check the errors below.[/red]")
        for e in errors[:5]:
            console.print(f"  {e}")
        return

    s = stats(latencies)
    print_stats_table(s, f"GET /workflow?status={STATUS}&page={PAGE}&size={SIZE} — {LABEL}")

    avg_size  = round(sum(sizes) / len(sizes), 0) if sizes else 0
    throughput = round(len(latencies) / elapsed, 2)
    console.print(f"\n  Records returned (avg) : [cyan]{avg_size:.0f}[/cyan] instances")
    console.print(f"  Throughput             : [cyan]{throughput}[/cyan] req/s")
    console.print(f"  Errors                 : [{'red' if errors else 'green'}]{len(errors)}[/]")
    console.print(f"  Total time             : [cyan]{elapsed:.1f}s[/cyan]")

    # ── Save JSON ─────────────────────────────────────────────────────────
    result = {
        "label":     LABEL,
        "timestamp": datetime.now().isoformat(),
        "config": {
            "base_url":        BASE_URL,
            "status_filter":   STATUS,
            "page":            PAGE,
            "size":            SIZE,
            "total_requests":  TOTAL_REQUESTS,
            "warmup_requests": WARMUP_REQUESTS,
            "concurrency":     CONCURRENCY,
            "timeout_seconds": TIMEOUT_SECONDS,
        },
        "stats":           s,
        "avg_result_size": avg_size,
        "throughput":      throughput,
        "error_count":     len(errors),
        "errors":          errors[:10],
    }
    out_path = f"benchmark_list_{LABEL}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    console.print(f"\n  [bold]Results saved to:[/bold] [cyan]{out_path}[/cyan]")
    console.rule()


if __name__ == "__main__":
    asyncio.run(main())