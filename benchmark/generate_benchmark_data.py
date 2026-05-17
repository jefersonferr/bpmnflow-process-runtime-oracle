"""
BPMNFlow benchmark data generator (Oracle)
===========================================
Real flow (as per Swagger):
  1. POST /workflow/start?versionId={id}
       body: { externalId?, variables? }
       → returns ProcessInstanceResponse with currentActivity.availableConclusions

  2. POST /workflow/{instanceId}/complete
       body: { conclusionCode?, variables? }
       → conclusionCode must be one of the codes in availableConclusions
       → omit conclusionCode if availableConclusions is empty
       → stop when instanceStatus == "COMPLETED" (or 409)

Usage:
  pip install httpx rich
  python generate_benchmark_data.py

  # available environment variables:
  BASE_URL=http://localhost:8080
  VERSION_ID=1                   → required: process version ID
  TOTAL_INSTANCES=10
  MIN_ACTIVITIES=10
  MAX_ACTIVITIES=30
  CONCURRENCY=20
"""

import asyncio
import random
import os
import time
import json
from datetime import datetime

import httpx
from rich.console import Console
from rich.progress import (
    Progress, SpinnerColumn, BarColumn,
    TaskProgressColumn, TimeElapsedColumn, TextColumn,
)

# ──────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────
BASE_URL        = os.getenv("BASE_URL",        "http://localhost:8080")
VERSION_ID      = int(os.getenv("VERSION_ID",  "1"))   # → adjust to the real versionId
TOTAL_INSTANCES = int(os.getenv("TOTAL_INSTANCES", "1000"))
MIN_ACTIVITIES  = int(os.getenv("MIN_ACTIVITIES",  "10"))
MAX_ACTIVITIES  = int(os.getenv("MAX_ACTIVITIES",  "30"))
CONCURRENCY     = int(os.getenv("CONCURRENCY",     "20"))
TIMEOUT_SECONDS = float(os.getenv("TIMEOUT_SECONDS", "30"))

console = Console()


# ──────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────
def random_variables(min_vars=1, max_vars=3):
    """Generates generic context variables to enrich the data."""
    keys = random.sample(
        ["department", "priority", "region", "category", "requestType"],
        k=random.randint(min_vars, max_vars),
    )
    values = {
        "department":  ["FINANCE", "HR", "LEGAL", "IT", "OPERATIONS"],
        "priority":    ["LOW", "MEDIUM", "HIGH", "CRITICAL"],
        "region":      ["NORTH", "SOUTH", "EAST", "WEST"],
        "category":    ["TYPE_A", "TYPE_B", "TYPE_C"],
        "requestType": ["NEW", "RENEWAL", "CANCELLATION", "AMENDMENT"],
    }
    return [{"key": k, "type": "STRING", "value": random.choice(values[k])} for k in keys]


def extract_instance_id(data: dict) -> int | None:
    return data.get("instanceId")


def extract_conclusions(data: dict) -> list[str]:
    """Returns the available codes from currentActivity.availableConclusions."""
    current = data.get("currentActivity") or {}
    return [c["code"] for c in current.get("availableConclusions", []) if c.get("code")]


def has_active_activity(data: dict) -> bool:
    """The workflow continues as long as currentActivity is present in the response."""
    return data.get("currentActivity") is not None


# ──────────────────────────────────────────────
# Async worker — one per instance
# ──────────────────────────────────────────────
async def create_instance(
    client: httpx.AsyncClient,
    semaphore: asyncio.Semaphore,
    instance_index: int,
    errors: list,
    progress: Progress,
    task_start,
    task_complete,
):
    async with semaphore:
        # ── 1. Start workflow ──────────────────────────────────────────────
        start_body = {
            "externalId": f"BENCH-{instance_index:05d}",
            "variables":  random_variables(min_vars=2, max_vars=4),
        }
        try:
            resp = await client.post(
                f"{BASE_URL}/workflow/start",
                params={"versionId": VERSION_ID},
                json=start_body,
            )
            resp.raise_for_status()
            data = resp.json()
        except Exception as exc:
            errors.append({"phase": "start", "index": instance_index, "error": str(exc)})
            progress.advance(task_start)
            return

        instance_id = extract_instance_id(data)
        if not instance_id:
            errors.append({
                "phase": "start", "index": instance_index,
                "error": f"instanceId missing from response: {resp.text[:300]}",
            })
            progress.advance(task_start)
            return

        progress.advance(task_start)

        # ── 2. Advance the workflow N times ───────────────────────────────
        # We draw how many activities we want in total; we already have 1,
        # so we call /complete N-1 times.
        target_activities = random.randint(MIN_ACTIVITIES, MAX_ACTIVITIES)

        for step in range(target_activities - 1):
            # Stop if there is no longer a current activity (workflow has ended)
            if not has_active_activity(data):
                break

            conclusions = extract_conclusions(data)

            # Build the /complete request body
            complete_body: dict = {"variables": random_variables(min_vars=0, max_vars=2)}
            if conclusions:
                # Randomly pick one of the valid codes returned by the API
                complete_body["conclusionCode"] = random.choice(conclusions)
            # if conclusions is empty → omit conclusionCode as instructed

            try:
                resp = await client.post(
                    f"{BASE_URL}/workflow/{instance_id}/complete",
                    json=complete_body,
                )
                if resp.status_code == 409:
                    # Instance already completed or no active activity — normal at end of flow
                    break
                resp.raise_for_status()
                data = resp.json()
            except httpx.HTTPStatusError as exc:
                errors.append({
                    "phase": "complete", "instanceId": instance_id,
                    "step": step, "status": exc.response.status_code,
                    "error": exc.response.text[:200],
                })
                break
            except Exception as exc:
                errors.append({
                    "phase": "complete", "instanceId": instance_id,
                    "step": step, "error": str(exc),
                })
                break

            # jitter to avoid synchronised load spikes
            await asyncio.sleep(random.uniform(0.005, 0.03))


# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────
async def main():
    console.rule("[bold]BPMNFlow — Benchmark data generator[/bold]")
    console.print(f"  Base URL          : [cyan]{BASE_URL}[/cyan]")
    console.print(f"  Version ID        : [cyan]{VERSION_ID}[/cyan]")
    console.print(f"  Instances         : [cyan]{TOTAL_INSTANCES}[/cyan]")
    console.print(f"  Activities/inst   : [cyan]{MIN_ACTIVITIES}–{MAX_ACTIVITIES}[/cyan]")
    console.print(f"  Concurrency       : [cyan]{CONCURRENCY}[/cyan] workers")
    console.print()

    errors: list = []
    semaphore = asyncio.Semaphore(CONCURRENCY)
    avg_completes = ((MIN_ACTIVITIES - 1) + (MAX_ACTIVITIES - 1)) / 2
    est_completes = int(TOTAL_INSTANCES * avg_completes)

    start_ts = time.time()

    async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            TimeElapsedColumn(),
            console=console,
        ) as progress:
            task_start    = progress.add_task("[green]POST /workflow/start   ", total=TOTAL_INSTANCES)
            task_complete = progress.add_task("[blue]POST /workflow/complete ", total=est_completes)

            await asyncio.gather(*[
                create_instance(client, semaphore, i, errors, progress, task_start, task_complete)
                for i in range(TOTAL_INSTANCES)
            ])

    elapsed = time.time() - start_ts
    start_errors = sum(1 for e in errors if e["phase"] == "start")
    success = TOTAL_INSTANCES - start_errors

    console.rule("Results")
    console.print(f"  Instances created : [green]{success}[/green] / {TOTAL_INSTANCES}")
    console.print(f"  Total errors      : [{'red' if errors else 'green'}]{len(errors)}[/]")
    console.print(f"  Total time        : [cyan]{elapsed:.1f}s[/cyan]")
    console.print(f"  Throughput        : [cyan]{success/elapsed:.1f}[/cyan] instances/s")
    console.print()
    console.print("[bold yellow]Note for the paper:[/bold yellow]")
    console.print(f"  → Instances in DB      : {success}")
    console.print(f"  → Activities/instance  : {MIN_ACTIVITIES}–{MAX_ACTIVITIES} (target), uniform random distribution")
    console.print(f"  → Version ID used      : {VERSION_ID}")

    if errors:
        log_path = f"errors_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        with open(log_path, "w") as f:
            json.dump(errors, f, indent=2, ensure_ascii=False)
        console.print(f"\n  [yellow]Errors saved to:[/yellow] {log_path}")
        console.print("  First 3 errors:")
        for e in errors[:3]:
            console.print(f"    {e}")

    console.rule()


if __name__ == "__main__":
    asyncio.run(main())