"""
Contention benchmark — PUT /workflow/{id}/variables
====================================================
Demonstrates three concurrency strategies for the same endpoint,
comparing behaviour, conflict visibility and cost.

Key finding:
  The Duality View applies OCC ALWAYS — even without If-Match in the header.
  When the service does findById + update, the _metadata containing the ETag
  read is sent back to Oracle on UPDATE. If another worker already committed,
  the ETag has changed and Oracle rejects with ORA-40896 → HTTP 412.

  This means "no ETag" on the DV is not a lost update — it is involuntary OCC.
  The client receives 412 without having requested protection.
  With an explicit If-Match, the client KNOWS it is performing OCC.

Strategies compared:

  MODE=JPA_NOCC  (dualityEnabled=false, no @Version)
    → Real lost update: all succeed, the last one silently overwrites the rest.
    → Demonstrates the baseline problem.

  MODE=DV  (dualityEnabled=true)
    Scenario B — no If-Match:
      → Automatic OCC by Oracle. 1 succeeds, N-1 receive 412.
      → Client did not request protection but the database protects anyway.
    Scenario C — with If-Match, no retry:
      → Explicit OCC. 1 succeeds, N-1 receive 412.
      → Client knows exactly what is happening.
    Scenario D — with If-Match + retry:
      → All eventually save. total_412s = contention cost.

  MODE=JPA  (dualityEnabled=false, with @Version)
    Scenario E — no If-Match:
      → @Version in Hibernate. 1 succeeds, N-1 receive 409.
      → Equivalent to B but at the JPA layer.

Usage:
  # Lost update baseline (no OCC at all):
  MODE=JPA_NOCC LABEL=JPA_NOCC  python benchmark_etag_contention.py

  # Duality View (scenarios B, C, D):
  MODE=DV       LABEL=DUALITY   python benchmark_etag_contention.py

  # JPA + @Version (scenario E):
  MODE=JPA      LABEL=JPA       python benchmark_etag_contention.py

Optional: CONCURRENCY_LEVELS=2,5,10  MAX_RETRIES=20  WARMUP_INSTANCES=3
"""

import asyncio, random, os, time, json
from datetime import datetime
import httpx, numpy as np
from rich.console import Console
from rich.table import Table

BASE_URL           = os.getenv("BASE_URL",             "http://localhost:8080")
VERSION_ID         = int(os.getenv("VERSION_ID",       "1"))
LABEL              = os.getenv("LABEL",                "DUALITY")
MODE               = os.getenv("MODE",                 "DV").upper()
CONCURRENCY_LEVELS = [int(x) for x in os.getenv("CONCURRENCY_LEVELS", "2,5,10").split(",")]
MAX_RETRIES        = int(os.getenv("MAX_RETRIES",      "20"))
WARMUP_INSTANCES   = int(os.getenv("WARMUP_INSTANCES", "3"))
TIMEOUT_SECONDS    = float(os.getenv("TIMEOUT_SECONDS", "30"))

VALID_MODES = ("JPA_NOCC", "DV", "JPA")
if MODE not in VALID_MODES:
    raise SystemExit(f"Invalid MODE: '{MODE}'. Use: {', '.join(VALID_MODES)}")

console = Console()

# ── Payload ────────────────────────────────────────────────────────────────────

def make_payload(worker_id: int) -> list[dict]:
    """Payload with a ~2 KB JSON variable to increase processing latency."""
    big_json = json.dumps({
        "workerId": worker_id,
        "timestamp": time.time(),
        "source": f"external-system-{worker_id}",
        "requestId": f"REQ-{worker_id:06d}-{random.randint(100000, 999999)}",
        "metadata": {f"field_{i}": f"value_{i}_{worker_id}" for i in range(40)},
        "history": [
            {"event": f"event_{j}", "ts": time.time() - j, "seq": j}
            for j in range(20)
        ],
    })
    return [
        {"key": "lastUpdater", "type": "STRING", "value": f"worker-{worker_id}"},
        {"key": "contextData", "type": "JSON",   "value": big_json},
    ]

# ── HTTP helpers ───────────────────────────────────────────────────────────────

def _etag(resp: httpx.Response) -> str | None:
    raw = resp.headers.get("ETag") or resp.headers.get("etag")
    return raw.strip('"') if raw else None

async def start_instance(client) -> int:
    resp = await client.post(
        f"{BASE_URL}/workflow/start",
        params={"versionId": VERSION_ID},
        json={"externalId": f"BENCH-VAR-{int(time.time()*1000)}-{random.randint(0,9999)}"},
    )
    resp.raise_for_status()
    return resp.json()["instanceId"]

async def get_instance(client, iid: int) -> tuple[str | None, dict]:
    resp = await client.get(f"{BASE_URL}/workflow/{iid}")
    resp.raise_for_status()
    return _etag(resp), resp.json()

async def put_variables(client, iid: int,
                        etag: str | None,
                        variables: list[dict]) -> tuple[int, str | None]:
    headers = {"If-Match": f'"{etag}"'} if etag else {}
    resp = await client.put(
        f"{BASE_URL}/workflow/{iid}/variables",
        json=variables, headers=headers,
    )
    return resp.status_code, _etag(resp)

async def get_last_updater(client, iid: int) -> str:
    resp = await client.get(f"{BASE_URL}/workflow/{iid}/variables")
    if resp.status_code != 200:
        return "N/A"
    for v in resp.json():
        if v.get("key") == "lastUpdater":
            return v.get("value", "N/A")
    return "N/A"

# ── Core: parallel GET + parallel PUT ─────────────────────────────────────────

async def contention_run(client, iid: int, concurrency: int,
                         use_etag: bool) -> list[dict]:
    """
    Phase 1 — parallel GET: all workers read the current state.
    Phase 2 — parallel PUT: all workers fire immediately afterwards.

    asyncio.gather in phase 1 ensures every GET completes before any PUT
    is sent, maximising collision probability.
    """
    # Phase 1: parallel GET
    worker_etags: list[str | None] = [None] * concurrency

    async def do_get(wid: int):
        etag, _ = await get_instance(client, iid)
        worker_etags[wid] = etag

    await asyncio.gather(*[do_get(i) for i in range(concurrency)])

    unique = set(e for e in worker_etags if e)
    console.print(f"  [dim]  GET: {concurrency} workers | "
                  f"unique ETags: {len(unique)} "
                  f"({'✓' if len(unique) <= 1 else '✗ divergent'})[/dim]")

    # Phase 2: parallel PUT (immediately after all GETs)
    results: list[dict] = [{}] * concurrency
    lock = asyncio.Lock()

    async def do_put(wid: int):
        my_etag   = worker_etags[wid] if use_etag else None
        variables = make_payload(wid)
        t0 = time.perf_counter()
        try:
            status, _ = await put_variables(client, iid, my_etag, variables)
            ms = max((time.perf_counter() - t0) * 1000, 0.1)
            async with lock:
                results[wid] = {
                    "worker_id":    wid,
                    "success":      status in (200, 204),
                    "conflict":     status in (409, 412),
                    "conflict_412": status == 412,
                    "conflict_409": status == 409,
                    "status":       status,
                    "latency_ms":   ms,
                    "retries": 0, "conflicts": 0,
                }
        except Exception as exc:
            async with lock:
                results[wid] = {"worker_id": wid, "success": False, "conflict": False,
                                 "error": str(exc), "latency_ms": 0.1,
                                 "retries": 0, "conflicts": 0}

    await asyncio.gather(*[do_put(i) for i in range(concurrency)])

    counts: dict = {}
    for r in results:
        s = str(r.get("status", "?"))
        counts[s] = counts.get(s, 0) + 1
    console.print(f"  [dim]  PUT: {counts}[/dim]")

    return results

# ── Scenario runners ───────────────────────────────────────────────────────────

async def run_lost_update(client, concurrency) -> tuple[dict, str]:
    """JPA without OCC — real lost update. All succeed, last one overwrites."""
    iid     = await start_instance(client)
    results = await contention_run(client, iid, concurrency, use_etag=False)
    agg     = aggregate(results)
    winner  = await get_last_updater(client, iid)
    agg["last_updater"] = winner
    return agg, winner

async def run_dv_no_ifmatch(client, concurrency) -> dict:
    """DV without If-Match — automatic OCC by Oracle, client did not request it."""
    iid     = await start_instance(client)
    results = await contention_run(client, iid, concurrency, use_etag=False)
    return aggregate(results)

async def run_dv_ifmatch(client, concurrency) -> dict:
    """DV with explicit If-Match, no retry — OCC visible to the client."""
    iid     = await start_instance(client)
    results = await contention_run(client, iid, concurrency, use_etag=True)
    return aggregate(results)

async def run_dv_retry(client, concurrency) -> dict:
    """DV with If-Match + retry — all workers eventually save their variables."""
    iid = await start_instance(client)
    results: list[dict] = []

    async def worker(wid: int):
        retries = 0; backoff = 30; t0 = time.perf_counter(); n_412s = 0
        while retries <= MAX_RETRIES:
            # Fresh GET on every attempt
            try:
                etag, _ = await get_instance(client, iid)
            except Exception as exc:
                results.append({"success": False, "conflict": False,
                                 "status": "GET_ERROR", "latency_ms": 0.1,
                                 "retries": retries, "n_412s": n_412s,
                                 "error": str(exc)}); return
            try:
                status, _ = await put_variables(client, iid, etag, make_payload(wid))
                if status in (200, 204):
                    results.append({
                        "success": True, "conflict": False, "status": status,
                        "latency_ms": (time.perf_counter()-t0)*1000,
                        "retries": retries, "n_412s": n_412s,
                    }); return
                elif status == 412:
                    n_412s += 1; retries += 1
                    await asyncio.sleep((backoff + random.uniform(0, backoff)) / 1000)
                    backoff = min(backoff * 2, 1000)
                    # continue loop — retry with fresh ETag
                else:
                    # Any other status (409, 500, etc.) — exit without retry
                    results.append({
                        "success": False, "conflict": False, "status": status,
                        "latency_ms": (time.perf_counter()-t0)*1000,
                        "retries": retries, "n_412s": n_412s,
                        "note": f"unexpected_status_{status}",
                    }); return
            except Exception as exc:
                results.append({"success": False, "conflict": False,
                                 "status": "PUT_ERROR", "latency_ms": 0.1,
                                 "retries": retries, "n_412s": n_412s,
                                 "error": str(exc)}); return

        # MAX_RETRIES exhausted
        results.append({"success": False, "conflict": False, "status": "MAX_RETRIES",
                         "latency_ms": (time.perf_counter()-t0)*1000,
                         "retries": retries, "n_412s": n_412s})

    await asyncio.gather(*[worker(i) for i in range(concurrency)])

    # Full diagnostics
    console.print(f"  [dim]  retry: {len(results)}/{concurrency} workers recorded a result[/dim]")
    status_counts: dict = {}
    for r in results:
        s = str(r.get("status", "?")); status_counts[s] = status_counts.get(s, 0) + 1
    console.print(f"  [dim]  retry status: {status_counts}[/dim]")
    if len(results) != concurrency:
        console.print(f"  [red]  ✗ {concurrency - len(results)} worker(s) did not record a result![/red]")

    agg = aggregate(results)
    agg["total_412s"]   = sum(r.get("n_412s", 0) for r in results)
    agg["total_retries"] = sum(r.get("retries", 0) for r in results)
    return agg

async def run_jpa_version(client, concurrency) -> dict:
    """JPA @Version — conflict detected by Hibernate → 409."""
    iid     = await start_instance(client)
    results = await contention_run(client, iid, concurrency, use_etag=False)
    agg     = aggregate(results)
    agg["conflicts_409"] = sum(1 for r in results if r.get("conflict_409"))
    return agg

# ── Aggregation ────────────────────────────────────────────────────────────────

def aggregate(results: list) -> dict:
    successes     = sum(1 for r in results if r.get("success"))
    conflicts     = sum(1 for r in results if r.get("conflict"))
    total_retries = sum(r.get("retries", 0) for r in results)
    total_412s_per_worker = sum(r.get("conflicts", 0) for r in results)
    lats   = [r["latency_ms"] for r in results if "latency_ms" in r]
    errors = [r for r in results if "error" in r]
    a = np.array(lats) if lats else np.array([0.1])
    return {
        "workers":       len(results),
        "successes":     successes,
        "conflicts":     conflicts,
        "total_retries": total_retries,
        "total_412s":    total_412s_per_worker,
        "conflicts_409": 0,
        "error_count":   len(errors),
        "latency": {
            "p50_ms":  round(float(np.percentile(a, 50)), 2),
            "p95_ms":  round(float(np.percentile(a, 95)), 2),
            "p99_ms":  round(float(np.percentile(a, 99)), 2),
            "max_ms":  round(float(np.max(a)), 2),
            "mean_ms": round(float(np.mean(a)), 2),
        },
    }

# ── Display ────────────────────────────────────────────────────────────────────

def print_table(label, rows, col412="HTTP 412", show_extra=False, extra_col=""):
    t = Table(title=label, show_header=True, header_style="bold cyan")
    t.add_column("Workers",  justify="center", style="bold")
    t.add_column("Success",  justify="center", style="green")
    t.add_column(col412,     justify="center",
                 style="blue" if "409" in col412 else "yellow")
    if show_extra:
        t.add_column(extra_col, justify="center", style="yellow")
        t.add_column("Retries",  justify="center")
    t.add_column("p50 (ms)", justify="right")
    t.add_column("p95 (ms)", justify="right")
    t.add_column("Errors",   justify="center", style="red")
    for r in rows:
        cv = str(r.get("conflicts_409", 0)) if "409" in col412 else str(r["conflicts"])
        row = [str(r["workers"]), str(r["successes"]), cv]
        if show_extra:
            row += [str(r.get("total_412s", 0)), str(r["total_retries"])]
        row += [str(r["latency"]["p50_ms"]), str(r["latency"]["p95_ms"]),
                str(r["error_count"])]
        t.add_row(*row)
    console.print(t)

# ── Warmup ─────────────────────────────────────────────────────────────────────

async def warmup(client):
    console.print(f"  [yellow]Warmup:[/yellow] {WARMUP_INSTANCES} instances ...")
    for i in range(WARMUP_INSTANCES):
        try:
            iid = await start_instance(client)
            await put_variables(client, iid, None, make_payload(i))
        except Exception:
            pass
    console.print("  [green]Warmup complete.[/green]\n")

# ── Mode runners ───────────────────────────────────────────────────────────────

async def run_mode_jpa_nocc(client, all_results):
    console.rule("[bold red]Lost Update — JPA without OCC[/bold red]")
    console.print(
        "  [dim]dualityEnabled=false, no @Version[/dim]\n\n"
        "  N workers perform GET (same state) and fire PUT simultaneously.\n"
        "  No concurrency mechanism — all return 200.\n"
        "  The last to commit silently overwrites the others.\n"
    )
    winners = []
    for concurrency in CONCURRENCY_LEVELS:
        console.print(f"  [dim]{concurrency} workers[/dim]")
        r, winner = await run_lost_update(client, concurrency)
        all_results["lost_update"].append(r)
        winners.append((concurrency, winner))

    print_table("Lost Update — all succeed, last one overwrites",
                all_results["lost_update"], col412="Conflicts")

    wt = Table(title="Who 'won'? (lastUpdater in DB after the test)",
               show_header=True, header_style="bold red")
    wt.add_column("Workers",     justify="center", style="bold")
    wt.add_column("lastUpdater", justify="center")
    wt.add_column("Verdict",     justify="left")
    for concurrency, winner in winners:
        wt.add_row(str(concurrency), winner,
                   "[yellow]Lost update — earlier workers lost their data[/yellow]")
    console.print(wt)
    console.print(
        "\n  [dim]→  All returned 200. The client has no way of knowing\n"
        "     that its data was overwritten. No visibility, no error.[/dim]\n"
    )


async def run_mode_dv(client, all_results):
    # B — DV without If-Match (automatic OCC)
    console.rule("[bold yellow]Scenario B — DV without If-Match (automatic OCC)[/bold yellow]")
    console.print(
        "  [dim]dualityEnabled=true, no If-Match header[/dim]\n\n"
        "  Oracle applies OCC automatically via the _metadata round-trip.\n"
        "  The client did not send If-Match, but the database still protects.\n"
        "  Expected: successes=1, HTTP 412=N-1 (protection invisible to the client).\n"
    )
    for concurrency in CONCURRENCY_LEVELS:
        console.print(f"  [dim]{concurrency} workers[/dim]")
        r = await run_dv_no_ifmatch(client, concurrency)
        all_results["dv_auto_occ"].append(r)
    print_table("Scenario B — DV without If-Match | automatic OCC",
                all_results["dv_auto_occ"])
    console.print(
        "\n  [dim]Note: 412 without If-Match is confusing for the client — it did not\n"
        "  request protection but was rejected. Scenario C resolves this explicitly.[/dim]\n"
    )

    # C — DV with If-Match, no retry
    console.rule("[bold red]Scenario C — DV with If-Match, no retry[/bold red]")
    console.print(
        "  [dim]dualityEnabled=true, explicit If-Match[/dim]\n\n"
        "  N workers send the ETag obtained in the GET inside If-Match.\n"
        "  Expected: successes=1, HTTP 412=N-1 (explicit and intentional OCC).\n"
    )
    for concurrency in CONCURRENCY_LEVELS:
        console.print(f"  [dim]{concurrency} workers[/dim]")
        r = await run_dv_ifmatch(client, concurrency)
        all_results["dv_etag_noretry"].append(r)
    print_table("Scenario C — DV If-Match no retry | success=1, 412=N-1",
                all_results["dv_etag_noretry"])
    console.print()

    # D — DV with If-Match + retry
    console.rule("[bold green]Scenario D — DV with If-Match + retry[/bold green]")
    console.print(
        f"  Fresh GET + PUT with ETag + retry up to {MAX_RETRIES}×.\n"
        "  All workers eventually save. total_412s = contention cost.\n"
    )
    for concurrency in CONCURRENCY_LEVELS:
        console.print(f"  [dim]{concurrency} workers[/dim]")
        r = await run_dv_retry(client, concurrency)
        all_results["dv_etag_retry"].append(r)

    print_table("Scenario D — DV If-Match + retry",
                all_results["dv_etag_retry"],
                show_extra=True, extra_col="Total 412s")
    console.print()


async def run_mode_jpa(client, all_results):
    console.rule("[bold blue]Scenario E — JPA @Version, no ETag[/bold blue]")
    console.print(
        "  [dim]dualityEnabled=false, @Version on WfProcessInstanceEntity[/dim]\n\n"
        "  N workers perform GET and fire PUT without If-Match.\n"
        "  Hibernate detects the conflict on commit → HTTP 409.\n"
        "  Expected: successes=1, HTTP 409=N-1.\n"
    )
    for concurrency in CONCURRENCY_LEVELS:
        console.print(f"  [dim]{concurrency} workers[/dim]")
        r = await run_jpa_version(client, concurrency)
        all_results["jpa_version"].append(r)
    print_table("Scenario E — JPA @Version | conflicts = HTTP 409",
                all_results["jpa_version"], col412="HTTP 409")
    console.print(
        "\n  [dim]JPA @Version vs DV ETag comparison:\n"
        "    @Version → 409, detected at commit by Hibernate\n"
        "    ETag DV  → 412, detected at UPDATE by Oracle\n"
        "  @Version cost: occ_version column on each entity + exception handler.\n"
        "  ETag DV cost:  no schema change — hash over the entire document.[/dim]\n"
    )

# ── Main ───────────────────────────────────────────────────────────────────────

async def main():
    mode_descriptions = {
        "JPA_NOCC": ("Lost Update baseline", "duality.enabled=false, no @Version"),
        "DV":       ("Duality View ETags",   "duality.enabled=true"),
        "JPA":      ("JPA @Version",         "duality.enabled=false, with @Version + V008"),
    }
    desc, prereq = mode_descriptions[MODE]

    console.rule(f"[bold]BPMNFlow — PUT /variables contention — {desc} ({LABEL})[/bold]")
    console.print(f"  Base URL  : [cyan]{BASE_URL}[/cyan]")
    console.print(f"  Mode      : [cyan]{MODE}[/cyan]  [dim]({prereq})[/dim]")
    console.print(f"  Workers   : [cyan]{CONCURRENCY_LEVELS}[/cyan]")
    console.print(f"  Endpoint  : [cyan]PUT /workflow/{{id}}/variables[/cyan]  "
                  f"[dim](JSON payload ~2 KB)[/dim]\n")

    all_results = {
        "label": LABEL, "mode": MODE,
        "timestamp": datetime.now().isoformat(),
        "config": {
            "base_url": BASE_URL, "version_id": VERSION_ID,
            "concurrency_levels": CONCURRENCY_LEVELS, "max_retries": MAX_RETRIES,
            "endpoint": "PUT /workflow/{id}/variables",
            "technique": "parallel_get_then_parallel_put",
        },
        "lost_update":     [],   # JPA_NOCC
        "dv_auto_occ":     [],   # DV without If-Match
        "dv_etag_noretry": [],   # DV with If-Match, no retry
        "dv_etag_retry":   [],   # DV with If-Match + retry
        "jpa_version":     [],   # JPA @Version
    }

    async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
        await warmup(client)
        if MODE == "JPA_NOCC":
            await run_mode_jpa_nocc(client, all_results)
        elif MODE == "DV":
            await run_mode_dv(client, all_results)
        else:
            await run_mode_jpa(client, all_results)

    # Summary
    console.rule("[bold]Summary[/bold]")
    s = Table(show_header=True, header_style="bold cyan")
    s.add_column("Scenario",   style="bold")
    s.add_column("Workers",    justify="center")
    s.add_column("Success",    justify="center")
    s.add_column("Conflicts",  justify="center")
    s.add_column("p50 ms",     justify="right")
    s.add_column("Detection",  justify="center")
    s.add_column("Correct?",   justify="center")

    if MODE == "JPA_NOCC":
        rows = [("lost_update", "Lost Update (JPA)", "-", "✗ data lost")]
    elif MODE == "DV":
        rows = [
            ("dv_auto_occ",     "B — DV w/o If-Match", "HTTP 412 auto", "✓ DB protects"),
            ("dv_etag_noretry", "C — DV If-Match",     "HTTP 412",      "✓ client knows"),
            ("dv_etag_retry",   "D — DV + retry",      "HTTP 412",      "✓ all save"),
        ]
    else:
        rows = [("jpa_version", "E — JPA @Version", "HTTP 409", "✓")]

    for key, lbl, det, ok in rows:
        for r in all_results[key]:
            c = r.get("conflicts", 0)
            s.add_row(lbl, str(r["workers"]), str(r["successes"]),
                      str(c), str(r["latency"]["p50_ms"]), det, ok)
    console.print(s)

    out = f"benchmark_etag_{LABEL}_{MODE}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(out, "w") as f:
        json.dump(all_results, f, indent=2, ensure_ascii=False)
    console.print(f"\n  Saved to: [cyan]{out}[/cyan]")

    next_steps = {
        "JPA_NOCC": "MODE=DV       LABEL=DUALITY python benchmark_etag_contention.py",
        "DV":       "MODE=JPA      LABEL=JPA     python benchmark_etag_contention.py",
        "JPA":      "All modes completed. Compare the JSON files for the paper.",
    }
    console.print(f"  Next: [cyan]{next_steps[MODE]}[/cyan]\n")
    console.rule()

if __name__ == "__main__":
    asyncio.run(main())