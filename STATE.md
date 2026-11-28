# State of the project

> **What comes next, in order:** [`docs/HANDOFF.md`](docs/HANDOFF.md) → Next. Plans: [`docs/plans/`](docs/plans); docs index: [`docs/README.md`](docs/README.md).

> **Where it stands (2026-11-27): app 3.1.8, server 2.1.0 live.** Partners (plan 13) and groups (plan 14) are in use on
> two phones. 3.1.6 tuned screen checks; 3.1.7 records crashes and connection problems and locked the log format;
> 3.1.8 saves the apps that skip the filter, in strict mode too. None of the three has been checked on a phone yet.
> **Next:** real use and an export, then a dry run with a real partner
> ([`docs/HANDOFF.md`](docs/HANDOFF.md) → Next).

> Screenshots: a clear image never leaves the phone. A flagged shot (NudeNet found an exposed body part) goes to partners
> as a **blurred** (≤ 48 px) thumbnail after a 10-minute note window (D-037, D-038); the owner can also send one.

_Last updated: 2026-11-27 (3.1.8). Update a row in the same commit as a change that moves it, and the latest release in the release commit. It is the
single place to answer "where are we?" — keep it honest, short, and dated._

## In one paragraph

**Focus Fence AC** is a free, open-source self-accountability app for Android, benchmarked against
**Ever Accountable** (EA). The core is the DNS-only filter, which works alone with no account or server. Optional
layers add an activity log, filter-health detection, settings friction and strict mode, uninstall protection (device admin),
opt-in text and screenshot capture with an on-device AI, and partners with end-to-end sealed reports through a
self-hostable server (live at `ff.flyboybyte.com` since 2026-11-23). Since plan 13 (D-042, released in 3.0, 2026-11-26)
there is one kind of partner: a person with devices, invited by code, QR code or email, reading the report in the Focus
Fence app or a browser; groups of up to 6 came in 3.1. The filter, logs, partners and groups have run on real phones;
strict mode passes its tests but hasn't been field-tested. The repo went public on 2026-11-24 (D-027).

## Where the code is

| | |
|---|---|
| Repo | `flyboy-byte/focusfence` (owner: flyboy-byte since 2026-11-24, transferred from sidewinderzz; public, D-027) |
| Branch | `main` is the only long-lived and default branch. Ordinary changes are pushed to it; risky ones and outside contributions go by squash-merged PR (D-043). |
| App id | `app.focusfence.accountable` ("Focus Fence AC") — installs **alongside** the original `app.focusfence` |
| Signing cert SHA-256 | `2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671` |
| Tests | about 330 app JVM unit tests + 61 server tests (incl. `PartnerFlowTest`); CI runs both on every push to `main` and every PR |
| Latest release | `v3.1.8-accountable` (2026-11-27, code 60): apps that skip the filter are saved, in strict mode too, and changing them doesn't alert partners. Earlier releases: [`CHANGELOG.md`](CHANGELOG.md). Server: `server-v2.1.0`, live since 2026-11-26, installed with the release `install.sh`. |

## Verification levels

Every feature below is tagged with how far it has actually been checked. Don't upgrade a tag
without evidence.

- **📱 Phone** — confirmed working on the owner's test phone (Pixel 9).
- **🧪 CI** — unit tests and the debug build pass; behaviour on a phone not yet confirmed.
- **📝 Prototype** — code exists but isn't built by CI or wired up.
- **❌ Not built**

## Feature status

### Filtering
| Feature | Status | Notes |
|---|---|---|
| DNS server choice: Automatic / Cloudflare / Cloudflare Families / Quad9 / Google / your own (UDP, hostname or DoH), optional fallback to Automatic | 🧪 | Settings → DNS server. Filtering happens before forwarding, so the choice never weakens blocking. Diag `fwd_chosen_failed` counts when your server didn't answer and the fallback did. |
| DNS-only VPN filter, relay via network DNS → DoH fallback | 📱 | **2026-11-20 (2.6.1): 0 failed of 1,544 forwards over 14 h; download speed normal with the VPN on (owner).** 2026-11-18 export: 2.0% forward failure (was 75.6% before the relay fix), median latency fine. v2.1.3+ runs with working internet. Fixed two broken relays (see Incidents). Blocking itself not re-checked on 2.1.3. |
| Owner screenshot review (`dns/ReviewPolicy`, `data/ReviewStore`, `ReviewActivity`): screenshots the AI scores ≥ your % (default 50, separate from the partner flag) are kept on the phone, viewable, answer logged as `rev` | 🧪 | Local only: `noBackupFilesDir` (excluded from backups), 720 px JPEG, deleted after 7 days or past 40, deleted when answered. Does not change partner evidence or reports. Diag: `shot_review_kept`, `shot_review_fail`; summary line "owner review". |
| Apps that skip the filter (`dns/ExclusionPolicy`): games, music and maps apply at once even in strict mode; everything that could show adult content needs the strict delay | 🧪 | Settings → Apps that skip the filter. Fixed in 3.1.8: the list didn't save newly ticked apps, a locked strict mode refused the restart that applies them, and the restart could alert partners (`dns/RestartGrace`). Risk comes from Android's app category, so a wrongly declared category is misfiled (unknown = guarded); a few open game apps are forced to guarded. A skipped app bypasses the filter completely. |
| Per-domain rules, schedules, budgets, section rules (original app) | 📱 | Inherited from upstream; unchanged. |
| Curated bundles (HaGeZi: security, threat, bypass on; adult/gambling/social/piracy/scam opt-in), Off/Report/Block | 🧪 | `specs/bundles.md`. Download on first run. |
| Block triage (D-024, D-034): a notification for own rules and adult/gambling/scam front doors, or nothing; Block history (sites / trackers); partner alert after 3 confirmed tries in 10 min | 🧪 | `dns/BlockTriage`, `dns/BlockHistory`, Blocking → Block notices / Block history. Partner tier needs the accessibility service. |
| Self-update (D-029 as amended): checks every time the app comes to the foreground, then every 20 min; notification + Home banner, What's new, one-tap install with signature check | 📱 | 2.18.1 offered 2.18.2 on switching back to the app, no tap (2026-11-25). Every update 2.14 → 2.20 installed this way. |
| User allowlist | 🧪 | |
| DoH-bypass guards | 📱 | Inherited. |
| App blocking (accessibility bounce) | 📱 | Inherited. |

### Detection and logging (accountability record, local only)
| Feature | Status | Notes |
|---|---|---|
| Activity log (dns, url, app, screen, enforce, svc, integ) + diag log (bat, perf, net, err, crash, exit) | 📱 | Exports read fine with `tools/analyze_logs.py`. Schema: `specs/event-log.md`. |
| Integrity trail (VPN/a11y/usage access/Private DNS/admin changes, restarts, log clears) | 📱 | Seen in a real export. |
| App-time timeline (UsageStats) | 🧪 | |
| Filter health (`VpnHealth`): lockdown, bypassed, silent, failing, degraded, offline | 🧪 | Alert + home row + `svc vpn health` record. |
| "Protection stopped" alert on revoke / failed start | 🧪 | |
| 3.1.7: crash and exit records, network changes, DNS failure reasons, update and report-page errors (`dns/Faults`, `monitor/ProcessFaults`) | 🧪 | Unit tests only. In the first export, check the "app & connection problems" section. |
| In-app text capture | 📱 | Seen in a real export. |
| Screenshots: sparse capture, flagged-only encrypted retention, always-on indicator | 📱 | Monitoring screen → "Is capture working?" shows the last capture, its AI verdict, totals, and what's blocking it (`dns/CaptureStatus`). |
| Plan 17 (3.1.5): lost-foreground fix (`UrlFilterService.watchingAnything`) | 📱 | Checked in a real export: the longest unread gap in a watched app fell from 10 min to 53 s. |
| Plan 17 (3.1.5): screen-text word index (`RiskTerms`, disguises, phrases), text alerts (app only, never the words), each picture checked on its own (`ImageRegions`), hot mode | 🧪 | Per-picture checks ran on a phone (~55 ms each). Matching checked by unit tests and a local replay. |
| 3.1.6: secret fields skipped (`SecretFields`), erotica words, quitting guard by distance, pictures next to explicit words at a lower bar (`FlagPolicy.inContext`), body check from 0.30 with `FEMALE_BOOST`, Flag sensitivity (`Sensitivity`) | 🧪 | Unit tests only. Watch `flag_context`, `flag_suggestive`, `body_female`, `body_runs`, `text_secret_skipped`. |
| AI flag policy (`dns/FlagPolicy`): a raw hit is reported/kept for a partner only if score ≥ 0.90, or a 2nd hit in the same app within 3 min, or ≥ 0.75 with adult words on screen in that app | 🧪 | Added 2026-11-23, 7 JVM tests, not on a phone. Untuned guesses; owner review screen unaffected. Diag counters `flag_strong/persistent/corroborated/held`. Since D-038 these rules decide only when NudeNet can't run; see the AI flagging row and 3.1.6. Don't retune without recording why (`docs/research/ai-detection.md`). |
| On-device AI flagging (optional, off by default) | 📱 | Two bundled models: GantMan MobileNet v2 (MIT, about 90-100 ms a screenshot) and NudeNet (D-038), which must find an exposed body part because the first alone gave false alarms. Unchanged screens skip the AI. Accuracy on real explicit content is untested. Battery cost: diag `clf`, `clf_ms`, `body_runs`, `body_ms`. History: `docs/research/ai-detection.md`. |

### Battery (Pixel 9, Focus Fence's own CPU; whole-phone %/h is too noisy to compare)
| Version | Screen on | Screen off | Notes |
|---|---|---|---|
| 2.4.1 (36 h) | 87 s CPU/h; ~28,500 accessibility events/h | 2.7 s/h | Accessibility event firehose was ~60% of it |
| 2.6.1 (14 h) | **38 s CPU/h; ~550 events/h** | 2.3 s/h | Biggest costs now: screenshots + AI ~7.6 s/h, DNS relay ~4.6 s/h, binder/GC from screen reads |
| 2.7.2 (5.4 h on, 25 h off; export of 2026-11-23, split by the `app updated` records) | **48 s CPU/h** (2.6.2 51, 2.6.3 55) | 3.75 s/h (was ~3.0) | Whole 122 h export averaged 59.5 s/h on, but that mixes 2.2 to 2.7. Screen-off is a bit up, probably DNS retries on the 2026-11-22 outage day. Per-version split needs the `app updated` svc records; `summary.txt` does not do it. |

### Tamper resistance and friction
| Feature | Status | Notes |
|---|---|---|
| Friction guards per action (None / Delay / Own PIN / Partner PIN) | 🧪 | `specs/friction-tiers.md`. Partner PIN is covered by a partner-held PIN or by asking a partner. Includes `LOWER_SENSITIVITY` (3.1.6). |

### Partner side
One kind of partner since plan 13 (D-042, 3.0): a person with devices. The earlier partner systems were removed and
their data deleted; their history is in `docs/plans/` and [`CHANGELOG.md`](CHANGELOG.md).

| Feature | Status | Notes |
|---|---|---|
| Server (Kotlin/Ktor, flat files): enrollment, invites, groups, sealed report slices, alerts, weekly report, admin page | 📱 | `server-v2.1.0` at `ff.flyboybyte.com`; Docker Compose or tarball with `install.sh` (D-033). 61 tests in CI, including `PartnerFlowTest`. Not done: off-box backups, key attestation at enroll. |
| Phone upload and heartbeat (WorkManager, about 15 min, hardware-backed P-256 key) | 📱 | `sync/SyncWorker`. Starts from enrollment, not old history. |
| Partners by code, QR code or email invite; one key per device; report in the app or a browser | 📱 | Tried on two phones (3.0, 2026-11-26). |
| Groups of up to 6, everyone sees everyone (plan 14, 3.1) | 📱 | Tried on two phones. |
| Instant alerts (filter off, protection loosened, screen checks off, log cleared, flags, leaving the server) | 🧪 | One rule: `Digest.alertText`. A "Filter turned off" email arrived end to end on the earlier partner system (2026-11-23); not rechecked kind by kind since plan 13. |
| "Phone went silent" after 60 min, and "checking in again" | 🧪 | Server `FF_STALE_MINUTES`. Deep Doze untested. |
| Weekly report email | 🧪 | |
| Ask a partner (one tap asks for an OK) | 🧪 | |
| Flagged screenshots to partners: automatic after a 10-minute note window, or owner-sent; blurred to 48 px at most, sealed | 🧪 | D-025, D-037, D-038. |
| Removing a partner is guarded and logged, tells them, and starts a new report key | 🧪 | |
| Hide chosen apps from reports; remove one person from a group | ❌ | |

## What's weak (read before trusting anything)

1. **Alerts are mostly unproven on phones.** Partners and groups have run on two phones, but each alert kind, asking a
   partner and the silence alert still need a deliberate run.
2. **Most features are 🧪, not 📱.** The relay looked fine in tests twice and failed on the phone
   twice. Assume any 🧪 item can fail the same way.
3. **Silence detection is measured on the charger only.** Check-ins held through a night on charge (2026-11-25); a long
   unplugged night, where deep Doze applies, is untested. If false "went silent" alerts show up, use a longer window at
   night.
4. **The AI's accuracy is unmeasured.** Flags need NudeNet to find a body part (D-038), which ended the known false
   alarms, but the bars (3.1.6: body check from 0.30, nudity from 0.30, Flag sensitivity 0.45/0.50/0.70) are untuned
   on real explicit content. Pictures the first model scores low are never checked. It only flags,
   never blocks. History: `docs/research/ai-detection.md`.
5. **Always-on VPN is easy to lose.** Turning the VPN off and on from the system switch switched Always-on off
   (2026-11-20). The integrity trail records it, and the app reminds the owner at most once per 12 h
   (`dns/AlwaysOnNudge`, diag counter `always_on_nudge`).

## Open decisions

Settled: hosting and domain (`ff.flyboybyte.com`), end-to-end sealed reports (D-023), AGPL-3.0 (D-038), going public
(D-027), one kind of partner (D-042), how changes land (D-043).

| Decision | Options |
|---|---|
| What comes after real testing | F-Droid (plan 11), partner polish, or push: owner's call |
| Dependency security updates | Dependabot security updates (plan 15, A4) |

## Next steps, in order

The ordered list is [`docs/HANDOFF.md`](docs/HANDOFF.md) → Next; each piece of work is a plan in `docs/plans/`.
Short version (2026-11-27):

1. Try 3.1.8: tick apps to skip the filter and reopen the list.
2. Real use, then a log export (analysed locally; nothing from it in the repo).
3. A dry run with a real partner: filter-off alert, an unplugged night, asking for an OK.
4. If low-scoring pictures still slip by: the body check on every picture while words are on screen (battery cost only
   in those windows). Open audit items in plan 15 (A3-A6).
5. Then, the owner's call on order: F-Droid (plan 11), partner polish, push.

## Incidents (what went wrong, so it isn't repeated)

| When | What | Cause | Fix |
|---|---|---|---|
| 2026-11-22 | Export summary showed 12.8% forward failure and 535 ms median latency | 16 hours with no working network (some hours had zero successful forwards, 2,782 of the 2,892 failures), likely travel; the relay was fine outside them: 0.57% failed, median 277 ms, p95 528 ms | None needed for the outage. Open: 3% of forwards still burn a first attempt before failover, and p99 reaches ~11 s; look at the UDP timeout before the next export. |
| pre-branch | Service woke thousands of times a day | Blocked queries answered NXDOMAIN with no SOA, so clients re-asked | Cacheable `0.0.0.0`/`::` with TTLs |
| 2026-11-16 | First-run CPU spike | Category reload storm on bundle download | Coalesced single-thread reload |
| 2026-11-16 | 75% of relays failing, slow browsing | Plain UDP to 1.1.1.1/8.8.8.8 unreliable on the test network | (superseded) |
| v2.1.1 | No DNS at all | DoH-only relay; also `disconnect()` killed keep-alive | v2.1.3: network's own resolver first, DoH fallback |
| v2.1.x | No internet while VPN on | Android lockdown ("Block connections without VPN") drops non-tunnel traffic | Health state `lockdown` + alert; never enable lockdown |
| v2.2–2.3.1 | Screenshots never taken, and no failure shown | Accessibility config lacked `canTakeScreenshot`; a thrown refusal wasn't counted; capture only ran on screen-change events, so a still page never triggered it | 2.3.2: capability added, every refusal logged (`err capture`), timer-driven capture while a watched app is in front |
| v2.3–2.4.1 | ~170 s CPU per screen-on hour-and-a-half; no URLs recorded in Brave Nightly | All-apps capture subscribed to every app's content-changed events (93k/32 h: binder + main + GC); text capture every 1.5 s; Brave Nightly missing from the address-bar map | 2.4.2: content events only for section rules; text/URL on a foreground tick (30 s / 4 s); more browser IDs |
| v2.1.0 | APK wouldn't install | Release was unsigned | CI signs; stable key via repo secrets |
| v2.0–2.6.0 | Downloads at ~14 kbps with the VPN on, full speed off | Android 10+ treats a VPN as metered unless told otherwise; apps throttle on metered networks | 2.6.1: `setMetered(false)` (inherit from the real network); owner confirmed speed normal; `vpn_metered` integrity key |
| ≤2.6.1 | Half-typed text logged as visited URLs (`h10.56.148.43`) | Address bar read while the user was editing it | 2.6.2: skip a focused address bar (`url_editing` counter) |
