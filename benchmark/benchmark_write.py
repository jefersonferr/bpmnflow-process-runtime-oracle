"""
Write benchmark — POST /workflow/start + POST /workflow/{id}/complete
=====================================================================
Measures the latency of write operations that touch multiple tables.

Methodology:
  1. Warmup phase: creates WARMUP_INSTANCES instances and completes each — discarded
  2. Measurement phase: creates TOTAL_INSTANCES instances, collects /start latencies
  3. For each real instanceId returned by /start, executes /complete

The real instanceId returned by the server is always used for /complete,
avoiding the bug of using sequential indices decoupled from the real ID.

Usage:
  pip install httpx rich numpy
  python benchmark_write.py

  # environment variables:
  BASE_URL=http://localhost:8080
  VERSION_ID=1
  TOTAL_INSTANCES=200
  WARMUP_INSTANCES=20
  CONCURRENCY=10
  LABEL=JPA_UCP
"""

import asyncio
import random
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
BASE_URL          = os.getenv("BASE_URL",          "http://localhost:8080")
VERSION_ID        = int(os.getenv("VERSION_ID",    "1"))
TOTAL_INSTANCES   = int(os.getenv("TOTAL_INSTANCES",   "200"))
WARMUP_INSTANCES  = int(os.getenv("WARMUP_INSTANCES",  "20"))
CONCURRENCY       = int(os.getenv("CONCURRENCY",       "10"))
LABEL             = os.getenv("LABEL",                 "JPA_UCP")
TIMEOUT_SECONDS   = float(os.getenv("TIMEOUT_SECONDS", "30"))

console = Console()

VARIABLE_KEYS = ["department", "priority", "region", "category", "requestType"]
VARIABLE_VALUES = {
    "department":  ["FINANCE", "HR", "LEGAL", "IT", "OPERATIONS"],
    "priority":    ["LOW", "MEDIUM", "HIGH", "CRITICAL"],
    "region":      ["NORTH", "SOUTH", "EAST", "WEST"],
    "category":    ["TYPE_A", "TYPE_B", "TYPE_C"],
    "requestType": ["NEW", "RENEWAL", "CANCELLATION", "AMENDMENT"],
}


def random_variables(min_vars=1, max_vars=3):
    keys = random.sample(VARIABLE_KEYS, k=random.randint(min_vars, max_vars))
    return [{"key": k, "type": "STRING", "value": random.choice(VARIABLE_VALUES[k])} for k in keys]


def extract_conclusions(data: dict) -> list:
    current = data.get("currentActivity") or {}
    return [c["code"] for c in current.get("availableConclusions", []) if c.get("code")]


def has_active_activity(data: dict) -> bool:
    return data.get("currentActivity") is not None


# ──────────────────────────────────────────────
# Worker: create instance + complete 1 activity
# Returns (start_ms, complete_ms) or None on error
# ──────────────────────────────────────────────
async def run_one(
    client,
    semaphore,
    index: int,
    errors: list,
):
    async with semaphore:
        # ── POST /workflow/start ───────────────────────────────────────────
        body = {
            "externalId": f"BENCH-WRITE-{index:06d}",
            "variables":  random_variables(min_vars=2, max_vars=3),
        }
        t0 = time.perf_counter()
        try:
            resp = await client.post(
                f"{BASE_URL}/workflow/start",
                params={"versionId": VERSION_ID},
                json=body,
            )
            resp.raise_for_status()
            start_ms = (time.perf_counter() - t0) * 1000
            data = resp.json()
        except Exception as exc:
            errors.append({"phase": "start", "index": index, "error": str(exc)})
            return None

        instance_id = data.get("instanceId")
        if not instance_id or not has_active_activity(data):
            return start_ms, None

        # ── POST /workflow/{instanceId}/complete ───────────────────────────
        conclusions = extract_conclusions(data)
        complete_body = {"variables": random_variables(min_vars=1, max_vars=2)}
        if conclusions:
            complete_body["conclusionCode"] = random.choice(conclusions)

        t0 = time.perf_counter()
        try:
            resp = await client.post(
                f"{BASE_URL}/workflow/{instance_id}/complete",
                json=complete_body,
            )
            if resp.status_code not in (200, 204):
                errors.append({
                    "phase":      "complete",
                    "instanceId": instance_id,
                    "status":     resp.status_code,
                    "error":      resp.text[:200],
                })
                return start_ms, None
            complete_ms = (time.perf_counter() - t0) * 1000
            return start_ms, complete_ms
        except Exception as exc:
            errors.append({"phase": "complete", "instanceId": instance_id, "error": str(exc)})
            return start_ms, None


# ──────────────────────────────────────────────
# Run phase (warmup or measurement)
# ──────────────────────────────────────────────
async def run_phase(client, n, offset, label, progress, errors, collect):
    start_latencies = []
    complete_latencies = []
    semaphore = asyncio.Semaphore(CONCURRENCY)
    task = progress.add_task(label, total=n)

    async def worker(i):
        result = await run_one(client, semaphore, offset + i, errors)
        if collect and result is not None:
            s_ms, c_ms = result
            if s_ms is not None:
                start_latencies.append(s_ms)
            if c_ms is not None:
                complete_latencies.append(c_ms)
        progress.advance(task)

    await asyncio.gather(*[worker(i) for i in range(n)])
    return start_latencies, complete_latencies


# ──────────────────────────────────────────────
# Statistics
# ──────────────────────────────────────────────
def stats(latencies):
    if not latencies:
        return None
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


def print_stats_table(s, title):
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
    console.rule(f"[bold]BPMNFlow — Write benchmark ({LABEL})[/bold]")
    console.print(f"  Base URL      : [cyan]{BASE_URL}[/cyan]")
    console.print(f"  Profile       : [cyan]{LABEL}[/cyan]")
    console.print(f"  Version ID    : [cyan]{VERSION_ID}[/cyan]")
    console.print(f"  Instances     : [cyan]{WARMUP_INSTANCES}[/cyan] warmup + "
                  f"[cyan]{TOTAL_INSTANCES}[/cyan] measured")
    console.print(f"  Concurrency   : [cyan]{CONCURRENCY}[/cyan] workers\n")

    errors = []

    async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(), TaskProgressColumn(), TimeElapsedColumn(),
            console=console,
        ) as progress:

            # ── Warmup ────────────────────────────────────────────────────
            console.print("  [yellow]Phase 1/2:[/yellow] warming up ...")
            await run_phase(
                client, WARMUP_INSTANCES, 0,
                "[yellow]Warmup (discarded)         ",
                progress, errors, collect=False,
            )

            # ── Measurement ───────────────────────────────────────────────
            console.print("\n  [green]Phase 2/2:[/green] measuring ...")
            start_ts = time.perf_counter()
            start_latencies, complete_latencies = await run_phase(
                client, TOTAL_INSTANCES, WARMUP_INSTANCES,
                "[green]POST /workflow/start       ",
                progress, errors, collect=True,
            )
            elapsed = time.perf_counter() - start_ts

    # ── Results ───────────────────────────────────────────────────────────
    console.print()
    s_start    = stats(start_latencies)
    s_complete = stats(complete_latencies)

    if s_start:
        print_stats_table(s_start, f"POST /workflow/start — {LABEL}")
    else:
        console.print("[red]No /start latencies recorded[/red]")

    console.print()

    if s_complete:
        print_stats_table(s_complete, f"POST /workflow/complete — {LABEL}")
    else:
        console.print("[red]No /complete latencies recorded[/red]")
        if errors:
            console.print("\n  First errors:")
            for e in errors[:5]:
                console.print(f"  {e}")

    total_ops  = len(start_latencies) + len(complete_latencies)
    throughput = round(total_ops / elapsed, 1) if elapsed > 0 else 0
    console.print(f"\n  Total throughput : [cyan]{throughput}[/cyan] ops/s "
                  f"({len(start_latencies)} starts + {len(complete_latencies)} completes)")
    console.print(f"  Total errors     : [{'red' if errors else 'green'}]{len(errors)}[/]")
    console.print(f"  Total time       : [cyan]{elapsed:.1f}s[/cyan]")

    # ── Save JSON ─────────────────────────────────────────────────────────
    result = {
        "label":     LABEL,
        "timestamp": datetime.now().isoformat(),
        "config": {
            "base_url":          BASE_URL,
            "version_id":        VERSION_ID,
            "total_instances":   TOTAL_INSTANCES,
            "warmup_instances":  WARMUP_INSTANCES,
            "concurrency":       CONCURRENCY,
        },
        "start":    s_start,
        "complete": s_complete,
        "throughput_ops_per_sec": throughput,
        "error_count": len(errors),
        "errors":      errors[:20],
    }
    out_path = f"benchmark_write_{LABEL}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    console.print(f"\n  [bold]Results saved to:[/bold] [cyan]{out_path}[/cyan]")
    console.rule()


if __name__ == "__main__":
    asyncio.run(main())