# Measurement plan: is it working, and what does it cost?

This plan uses the logging added on this branch (spec: [`../specs/event-log.md`](../specs/event-log.md))
to answer three questions with data instead of assumptions:

1. **Is protection actually running?** Coverage and integrity.
2. **Is detection/filtering doing its job?** Resolver health, URL capture rate, list hits.
3. **What does each capability cost?** Battery and CPU.

The thresholds below are *proposed starting points* for decisions, not measured facts. Change
them once real numbers exist.

## Tools

| Tool | What it sees | Limits |
|---|---|---|
| In-app **diag log** (Logs & diagnostics → Export) | Whole-device battery % and µAh per screen state; Focus Fence's own CPU ms; DNS latency p50/p95/p99, failures, failovers; accessibility event volume; URL-read misses; log loss; errors. | Samples only while the VPN or accessibility service runs. Battery % is the whole device, not this app's share. |
| In-app **activity log** | What was detected/blocked, app timeline, integrity changes, service lifecycle. | On-device only until sync exists. |
| `tools/analyze_logs.py` | Summaries off-device: coverage gaps, seq gaps, per-hour activity, foreground time per app, CSVs. | — |
| `tools/battery-baseline.sh` (adb `batterystats`) | Per-app power attribution, wakelocks, and baseline runs with the app *off*. | Needs adb; a USB connection counts as charging unless the script's `unplug` is used. |
| Perfetto / Battery Historian (`adb bugreport`) | Wakeups and per-thread CPU over time. | Manual analysis. |

## Protocol

- Same phone, same normal usage, unplugged, **8h+ per run** (overnight screen-off runs are
  especially telling), at least **2 runs per configuration**.
- Before each run: start `battery-baseline.sh start`, then use the phone normally.
- After each run: `battery-baseline.sh stop <config>-<n>`, then export the in-app logs into the
  same folder.
- Compare **configurations against each other**, never against "zero". Device drain varies day
  to day by more than a light app costs, so look for differences that repeat across runs.

## Configurations (from the handoff's benchmark list)

| # | Configuration | How to set it up | Primary numbers |
|---|---|---|---|
| 1 | App installed, all protection off | VPN off, accessibility off | batterystats only (baseline) |
| 2 | DNS VPN, no ad list | VPN on, ad list off, "Record allowed lookups" off | screen-off %/h, app cpu ms/h, dns q/h |
| 3 | DNS VPN + ad list | ad list on | same, plus `adlist loaded ms` (startup filtering gap) |
| 4 | + allowed-lookup logging | "Record allowed lookups" on | delta vs 3: the cost of full DNS detection |
| 5 | + UsageStats timeline | grant Usage access | delta vs 4 (expected ~0: pulled, not pushed) |
| 6 | + accessibility, app-only | block one app, no section rules, URL recording off | `a11y_ev`/h, delta vs 5 |
| 7 | + browser URL recording | "Record browser addresses" on | `a11y_ev`/h, screen-on delta vs 6, `url_miss` rate |

Later configurations (screenshots + classifier, Device Owner network logging, full-TUN) slot in
as rows 8–12 when those exist. The same metrics apply.

## What each result decides

| Signal | Where | Proposed reading |
|---|---|---|
| VPN down intervals | analyzer "protection coverage" | Any unexplained gap (no `stop`/`revoked` by the user) means the OS killed it. Check `battery_opt_exempt` in meta, then always-on VPN. Repeated gaps mean survival work comes before features. |
| `a11y_bound=false` integrity changes without a user action | integrity changes | OEM killing the accessibility service. This is the reliability problem Truple/EA report too; it decides whether accessibility-based detection is dependable on this phone. |
| Activity `seq` gaps | analyzer "seq gaps" | Lines lost to process kills before a flush. If frequent, shorten the flush interval. |
| `fwd_fail` rate while online | perf | Above ~1–2% means users see failed lookups; investigate the relay before adding load. |
| p95 latency | perf | Compare with a run without the VPN (Private DNS off, plain DNS). If FF adds a lot, look at the 4-thread pool and upstream choice. |
| `url_miss` rate | perf | Above ~20% means `URL_BAR_IDS` is stale for the browser you use, or the toolbar is hidden. Fix before relying on URL reports. |
| Config 4 vs 3 cost | battery | Decides whether "record allowed lookups" can stay default-on. |
| Config 7 vs 6 cost | battery + `a11y_ev` | Decides whether in-app content capture (parity Phase 2) is affordable, since it widens accessibility further. |
| Lookups for front-door hosts vs actual app time | activity log vs UsageStats | Tells you how far DNS-inferred budgets (`UsageTracker`) are from real foreground time. Decides whether to switch budgets to UsageStats. |

## Questions from the handoff this plan answers

- Q5 (does accessibility survive OEM battery management?): integrity trail and coverage gaps.
- Q10 (what monitoring gaps remain?): coverage, `url_miss`, and how DNS hosts compare with
  UsageStats apps.
- Q11 (minimum remote data for a useful report): record volume per day per type from the
  analyzer, with a judgment of which types the report actually needed.
- Experiment 3 (DNS VPN battery baseline): configs 1–3.
- Experiment 4 (event volume): records/day and bytes/day from the export.
