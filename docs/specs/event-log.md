# Event log specification (schema v1)

**Status: implemented on this branch.** Code: `dns/LogEvents.kt` (schema), `dns/LogJson.kt`
(codec), `data/EventLog.kt` (storage), `monitor/Monitor.kt` (battery, perf, integrity),
`monitor/UsageCollector.kt` (app timeline). Off-device reader: `tools/analyze_logs.py`.

## Purpose and channels

There are two logs, because they answer different questions for different readers:

| Channel | Question | Reader | File |
|---|---|---|---|
| **activity** | What happened on this phone, and was protection on? | The owner now; an accountability partner later (via sync). | `files/logs/activity.jsonl` |
| **diag** | How well is the app itself working, and what does it cost? | Whoever is building/tuning it. Never needed by a partner. | `files/logs/diag.jsonl` |

Everything is app-private storage. Nothing leaves the phone except through a user-initiated
export.

## Format

- JSON Lines: one flat JSON object per line (no nesting), UTF-8, `\n` terminated.
- Every line has `seq` (per-channel, strictly increasing, survives restarts and clears), `t`
  (epoch ms, wall clock) and `type`.
- Lines are in **write order**. `t` order is not guaranteed: UsageStats events are pulled after
  the fact and carry their own earlier timestamps. Sort by `t` for timelines, and use `seq` for
  completeness checks.
- Integers are JSON integers. Readers must ignore unknown fields and skip unparseable lines (a
  kill mid-write can tear the last line).

## Record types

### Activity channel

| `type` | Fields | Written when |
|---|---|---|
| `dns` | `host`, `qtype` (`A`, `AAAA`, `HTTPS`, … or number), `decision` (`allowed`/`blocked`), `reason` (`rule`/`ads`/`guard`, absent if nothing matched), `rule` + `mode` (`always`/`window`/`budget`/`section`) when a user rule covers the host **even if it didn't block** | Every blocked query; every allowed query while *Record allowed lookups* is on (default on) |
| `url` | `pkg` (browser), `host`, `path` (no query, no fragment), `cat`? (bundle categories the host matches) | A browser's address bar shows a different page, while *Record browser addresses* is on (default off) |
| `app` | `pkg`, `state` (`fg`/`bg`) | The foreground package changes (UsageStats; needs Usage access) |
| `screen` | `state` (`on`/`off`) | Screen interactivity changes (UsageStats) |
| `enforce` | `action` (`app_home`, `section_back`, `block_screen`), `pkg`, `host`?, `path`? | The accessibility service enforced a rule (`block_screen`: opened the "Blocked by Focus Fence" screen over a browser showing a DNS-blocked site) |
| `text` | `pkg`, `text` (visible on-screen text, length-capped, passwords skipped), `hits`? (`host:cat,cat;…` — sites named on screen that match a bundle), `terms`? (`cat:word,word;…` — risky words, `RiskTerms`), `cat`? | Experimental text capture, watched apps only, while the capture indicator shows |
| `rev` | `shot_t`, `pkg`, `label`, `pct`, `verdict` (`ok` real hit / `wrong` not a problem) | The owner's answer on a review screenshot (Monitoring → Review); feeds threshold tuning, never changes partner reports |
| `shot` | `pkg`, `score`?, `label`?, `kept` (bool), `id`? (if an encrypted image was retained), `model`?, `scores`? (every label, `label=0.000,…`), `flagged`?, `ms`? (inference), `trigger`? (`timer`/`event`), `same`? (screen unchanged since the last scored capture; AI skipped, previous verdict reused) | Experimental screenshot capture: one classified screenshot |
| `svc` | `comp`, `state`, `detail`? | Component lifecycle (table below) |
| `integ` | `key`, `value`, `was` (null = first observation) | An observed protection state changed (table below). Always flushed immediately. |

`svc` lifecycle states:

| `comp` | `state` values |
|---|---|
| `partner` | `server_enrolled`, `invite_created`, `partner_removed` (detail: name), `pairing_created` / `pairing_removed` (detail: name) |
| `vpn` | `start` (detail: rule count, exclusions, ad list, always-on), `stop` (`user` or `restart`), `stop_refused` (strict), `revoked`, `destroyed`, `establish_failed`, `health` (detail: `ok`/`offline`/`degraded`/`upstream_down`/`silent`/`bypassed`/`lockdown`, `fail_pct`, `was`) |
| `a11y` | `connected` (detail: scope), `destroyed` |
| `app` | `process_start` (detail: boot count, version), `updated` (from/to) |
| `device` | `rebooted` |
| `log` | `setting` (a recording switch changed), `exported`, `cleared` |

`integ` keys:

| key | values | Notes |
|---|---|---|
| `vpn` | `running`/`stopped` | Skipped during the first 20 s after a process start (services re-binding) |
| `vpn_always_on` | `on`/`lockdown`/`off`/`unknown` | Only while the VPN runs. `unknown` before Android 10 |
| `a11y_enabled` | `true`/`false` | The system setting |
| `a11y_bound` | `true`/`false` | Whether the service is actually connected. Enabled-but-unbound means the OEM killed it. Startup grace as for `vpn` |
| `usage_access` | `true`/`false` | |
| `private_dns` | `off`/`opportunistic`/`hostname`/`default`/`unknown` | Private DNS routes lookups around the filter |
| `capture_screenshots`, `capture_text` | `true`/`false` | Experimental capture turned on/off (an accountability event) |

Integrity is checked when the app process starts, when either service attaches or detaches, on
every battery sample, and when the diagnostics screen opens or an export runs.

### Diag channel

| `type` | Fields | Written when |
|---|---|---|
| `bat` | `level` (%), `plugged`, `screen`, `cpu` (this process's CPU ms), `up` (elapsedRealtime ms), `pid`, `uah`? (charge counter µAh), `ua`? (current µA, OEM sign convention), `temp`? (0.1 °C) | Battery level/plug change, screen on/off, or 15 min since the last sample. Only from system broadcasts, never a timer |
| `perf` | `win` (window ms) + counters below + `p50`/`p95`/`p99`/`max` resolver latency ms | Right after each `bat` sample, closing the window since the previous one |
| `err` | `comp`, `what`, `detail`? (≤300 chars) | Non-fatal failures: `ipv6_rejected`, `tun_read`, `interrupt`, monitor exceptions |
| `svc` | `comp=adlist`, `state=loaded`, `detail` (`n=…,ms=…`) | Ad list loaded after VPN start. Until then ad domains resolve normally |

`perf` counters (absent = 0):

| Counter | Meaning |
|---|---|
| `q` | DNS queries received on the tunnel |
| `q_unparsed` | Queries that couldn't be parsed (forwarded unfiltered) |
| `pkt_unparsed`, `pkt_not_dns` | Non-UDP/fragmented packets; UDP not to port 53 |
| `blk_rule`, `blk_ads`, `blk_guard` | Blocks by layer |
| `fwd` | Forwarded queries answered (latency recorded) |
| `fwd_attempt_fail` | Individual upstream attempts that timed out or errored |
| `fwd_failover` | Answered, but not by the preferred upstream |
| `fwd_fail` | No upstream answered; the client got nothing |
| `fwd_unprotected` | `protect()` failed |
| `tun_write_fail` | Reply couldn't be written to the tunnel |
| `a11y_ev` | Accessibility events delivered (the battery-relevant number) |
| `shot`, `shot_flagged`, `shot_fail`, `shot_decode_fail`, `shot_save_fail`, `shot_no_partner_key` | Screenshots captured / flagged / failures at each stage |
| `shot_req`, `shot_timer`, `shot_busy`, `shot_timer_left_app`, `shot_fail_c<code>` | Capture requests (and how many came from the timer), skipped while one was in flight, timer stopped because another app was in front, failures by Android error code (2 no permission, 6 app blocks screenshots) |
| `clf`, `clf_ms`, `clf_cpu_us`, `clf_load_ms`, `clf_load_fail`, `clf_top_<label>`, `clf_risk_b<0-9>` | AI: inferences, wall and CPU time, model load, winning label counts, risk-score deciles (threshold tuning) |
| `shot_same` | Captures where the screen hadn't changed (dHash within 5 bits, same app, ≤10 min) so the AI was skipped |
| `text_captured`, `text_empty`, `text_nodes`, `text_truncated`, `text_cat_hits`, `text_cat_<cat>`, `text_term_hits`, `text_term_<cat>`, `url_cat_hits`, `fg_tick` | On-screen text and address-bar detection: captures, nodes walked, bundle-site hits |
| `thr` record (diag): `top` (`name=ms;…;other=ms`), `total`, `screen` | Whole-process CPU by thread since the previous battery sample, from /proc — catches UI, GC, AI runtime threads |
| `dns_cpu_us`, `fwd_cpu_us`, `a11y_cpu_us`, `text_cpu_us`, `shot_cpu_us`, `sync_cpu_us`, `sync`, `sync_fail` | Thread-CPU attribution per component (µs), so battery cost can be traced to a part of the app |
| `text_captured`, `text_empty` | Text captures written / screens with no text |
| `url_read`, `url_miss`, `url_editing` | Browser address bar read / not found / skipped because the user was typing in it |
| `lat_n` | Latencies recorded (percentiles use at most 4096 per window) |
| `log_dropped` | Log lines that failed to write |

## Storage guarantees

- Writes are buffered and flushed on a dedicated thread after 200 lines or 30 s (checked on
  the next append). The DNS reader thread never touches the disk.
- `svc` and `integ` records are **urgent**: flushed immediately, so a protection gap is on disk
  before anything can kill the process.
- Up to 30 s of other records can be lost if the process is killed. Process starts are logged,
  and `seq` gaps expose the loss.
- Rotation: activity at 8 MB × 4 files (~32 MB), diag at 4 MB × 3 files. The oldest file is
  dropped.
- Clearing deletes the files, keeps `seq` counting, and writes `svc log cleared` as the first new
  line. Clearing and turning a recording switch off both respect strict mode.

## Export

Logs & diagnostics → Export produces `focusfence-logs-<yyyyMMdd-HHmm>.zip`, shared through the
system share sheet (FileProvider, `cache/export/` only). It contains:

- `meta.json`: schema version, app version, manufacturer/model/SDK, configuration (rule count,
  ad list, recording switches, Usage access, accessibility, battery-optimization exemption).
- `summary.txt`: the same report the diagnostics screen shows.
- `activity*.jsonl`, `diag*.jsonl`: the raw logs.

## Privacy notes

- DNS hostnames are logged for lookups from every app that goes through the tunnel. Apps in
  VPN exclusions bypass it and produce no records, so an exclusion is a detection blind spot.
  The count is in each `svc vpn start` record, and reports should surface it.
- URLs are host + path only. Query strings carry search terms and tokens and are dropped at
  capture time, so they never reach the log.
- Nothing is transmitted. Sync, when it exists, will need its own spec covering what leaves the
  phone (see `accountability-architecture.md`).

## Known gaps

- When neither service runs and the app isn't opened, nothing observes changes. The next check
  reports them at detection time. Only a server-side heartbeat closes this.
- DNS cannot attribute a lookup to an app. Pairing `dns` with `app` foreground records by time
  is a heuristic. Device Owner network logging (`DnsEvent` has a package name) is the real fix.
- Allowed-lookup volume is high (one line per query, including repeats). That's deliberate for
  measurement. Aggregation belongs in reports, not capture.

## Versioning

`LogEvents.SCHEMA_VERSION` is written into `meta.json`. Adding fields or record types doesn't
bump it, since readers ignore unknown fields. Renaming or changing the meaning of a field does.

## `net` (diag, added after 2.9.2)
One record at each battery sample: `tr` (cell / wifi / other / none), `val` (Android's validated flag), `roam`, `metered`, `down` / `up`
(the modem's own bandwidth estimate in kbps, not a measured speed), `sig` (dBm when Android reports it). No traffic, no permission.
Lines up with the `perf` latency windows to tell "slow lookups on a weak cell link" from "slow lookups on good signal".
`perf` also gained `block_screen` (the blocked screen was opened over a browser; see `dns/BlockScreen`).
`perf` also gained `fwd_hedged` (a second resolver was started beside a slow one; Automatic DNS only, see `dns/Hedged`).
