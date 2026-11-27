# State of the project

> **What comes next, in order:** [`docs/HANDOFF.md`](docs/HANDOFF.md) → Next. Plans: [`docs/plans/`](docs/plans); docs index: [`docs/README.md`](docs/README.md).

> **Where it stands (2026-11-27): app 3.1.6, server 2.1.0 live.** Partners (plan 13) and groups (plan 14) are in use on
> two phones. 3.1.5 (plan 17) was checked on a phone: the blind-spot fix works (longest unread gap 53 s). 3.1.6: secret
> fields never read, written erotica matched, pictures next to explicit words flagged at a lower bar, body check from
> 0.30 with a small boost when a woman is in the picture, and Flag sensitivity (Strict / Standard / Relaxed, less strict
> guarded). None of 3.1.6 seen on a phone yet. **Next:** a test on the same kind of page, then an export
> ([`docs/HANDOFF.md`](docs/HANDOFF.md) → Next).

> Screenshots: a clear image never leaves the phone. A flagged shot (NudeNet found an exposed body part) goes to partners
> as a **blurred** (≤ 48 px) thumbnail after a 10-minute note window (D-037, D-038); the owner can also send one.

_Last updated: 2026-11-27 (3.1.6). Update this file in the same commit as any change that moves an item below. It is the
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
| Branch | `main` is the only long-lived branch (D-022); work lands by squash-merged PR (squash-only, branches auto-deleted). `main` is the default branch. |
| App id | `app.focusfence.accountable` ("Focus Fence AC") — installs **alongside** the original `app.focusfence` |
| Signing cert SHA-256 | `2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671` |
| Tests | about 308 app JVM unit tests + 61 server tests (incl. `PartnerFlowTest`); CI runs both on every PR |
| Latest release | `v3.1.6-accountable` (2026-11-27, code 58): secret fields skipped, erotica words, quitting guard by distance, pictures next to explicit words flagged, body check from 0.30 with a female boost, Flag sensitivity. 3.1.5: plan 17 screen text and screenshots (word index with disguises and phrases, alerts with a quitting-screen guard, each picture checked on its own, faster deeper reads after a flag, fix for 10-minute blind spots after leaving Focus Fence). 3.1.4: targetSdk 35 (Android 15 edge-to-edge handled by `ui/EdgeToEdge`), compileSdk 35, AGP 8.7.3, library updates. 3.1.3: plan 15 fixes (push note in the rule editor, lint triage, screenshot checker leak). 3.1.2: invites shared as a QR picture with the code, one join box for code/link/message, Scan a QR code; 3.1.1 slowed the address-bar poll during videos; 3.1.0 brought plan 14 groups (Logan: "looks amazing"). Per-release detail: [`CHANGELOG.md`](CHANGELOG.md). Server: `server-v2.1.0`, live since 2026-11-26 15:39 UTC, installed with the release `install.sh`. |

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
| Owner screenshot review (`dns/ReviewPolicy`, `data/ReviewStore`, `ReviewActivity`): screenshots the AI scores ≥ your % (default 50, separate from the 70% partner flag) are kept on the phone, viewable, answer logged as `rev` | 🧪 | Local only: `noBackupFilesDir` (excluded from backups), 720 px JPEG, deleted after 7 days or past 40, deleted when answered. Does not change partner evidence or reports. Diag: `shot_review_kept`, `shot_review_fail`; summary line "owner review". |
| VPN exclusions by risk (`dns/ExclusionPolicy`): games/music/maps toggle instantly even in strict mode; everything that could show adult content needs the strict delay | 🧪 | Settings → VPN exclusions. Added because games with anti-cheat refuse to run behind a VPN. Risk comes from Android's app category, so an app that declares the wrong category is misfiled (unknown = guarded); a few open games (Roblox, Discord) are forced to guarded. Excluded apps bypass **all** filtering and capture-by-DNS, so this is a real hole for anything free. |
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
| Activity log (dns, url, app, screen, enforce, svc, integ) + diag log (bat, perf, err) | 📱 | Exports read fine with `tools/analyze_logs.py`. Schema: `specs/event-log.md`. |
| Integrity trail (VPN/a11y/usage access/Private DNS/admin changes, restarts, log clears) | 📱 | Seen in a real export. |
| App-time timeline (UsageStats) | 🧪 | |
| Filter health (`VpnHealth`): lockdown, bypassed, silent, failing, degraded, offline | 🧪 | Alert + home row + `svc vpn health` record. |
| "Protection stopped" alert on revoke / failed start | 🧪 | |
| In-app text capture | 📱 | 182 captures across 8 apps in the 2026-11-18 export. |
| Screenshots: sparse capture, flagged-only encrypted retention, always-on indicator | 📱 | Monitoring screen → "Is capture working?" shows the last capture, its AI verdict, totals, and what's blocking it (`dns/CaptureStatus`). |
| Plan 17 (3.1.5): screen-text word index, text alerts (app only, never the words), quitting-page guard, pictures checked on their own, hot mode, lost-foreground fix | 🧪 | Unit tests and a local replay of a real export (7,418 screens: 1 alert in 9 days). Not seen on a phone; next export checks `fg_recovered`, `region_*`, `shot_hot`, `text_hot` |
| AI flag policy (`dns/FlagPolicy`): a raw hit is reported/kept for a partner only if score ≥ 0.90, or a 2nd hit in the same app within 3 min, or ≥ 0.75 with adult words on screen in that app | 🧪 | Added 2026-11-23, 7 JVM tests, not on a phone. Untuned guesses; owner review screen unaffected. Diag counters `flag_strong/persistent/corroborated/held`. Don't retune without recording why (`docs/research/ai-detection.md`). |
| On-device AI flagging (optional, off by default) | 📱 | **2026-11-25 (2.22.0, 🧪): a partner flag needs NudeNet to find an exposed body part (D-038).** The GantMan model alone flagged video-call faces at 65–83%, a family photo and a hand with a chip; NudeNet found nothing exposed on all five. Not tested on real explicit content or on a phone yet; watch `body_runs`/`body_ms` and the review cards' "body check" line. **First real flag 2026-11-20 was a false positive:** a photo with no nudity scored `porn` 0.82. 236 screenshots scored so far, avg 91 ms, model load ~2 s; unchanged screens skip the AI (23 of 89 on 2.6.1). Options to cut false alarms without battery cost: `docs/research/ai-detection.md`. 2026-11-19: 41 screenshots scored, avg 100 ms, all neutral/drawings, model load 1.4 s. Bundled: GantMan MobileNet v2 NSFW (MIT), float16, 12 MB, all ops v1–v3 for the TFLite 2.14 runtime. Matched the original model on 35 synthetic inputs (same top label, ≤0.03 diff); not tested on real content or on a phone. Battery cost logged: diag `clf`, `clf_ms`, `clf_load_ms`, shown in the diagnostics report. |

### Battery (Pixel 9, Focus Fence's own CPU; whole-phone %/h is too noisy to compare)
| Version | Screen on | Screen off | Notes |
|---|---|---|---|
| 2.4.1 (36 h) | 87 s CPU/h; ~28,500 accessibility events/h | 2.7 s/h | Accessibility event firehose was ~60% of it |
| 2.6.1 (14 h) | **38 s CPU/h; ~550 events/h** | 2.3 s/h | Biggest costs now: screenshots + AI ~7.6 s/h, DNS relay ~4.6 s/h, binder/GC from screen reads |
| 2.7.2 (5.4 h on, 25 h off; export of 2026-11-23, split by the `app updated` records) | **48 s CPU/h** (2.6.2 51, 2.6.3 55) | 3.75 s/h (was ~3.0) | Whole 122 h export averaged 59.5 s/h on, but that mixes 2.2 to 2.7. Screen-off is a bit up, probably DNS retries on the 2026-11-22 outage day. Per-version split needs the `app updated` svc records; `summary.txt` does not do it. |

### Tamper resistance and friction
| Feature | Status | Notes |
|---|---|---|
| Friction guards per action (None / Delay / Own PIN / Partner PIN) | 🧪 | `specs/friction-tiers.md`. Partner PIN is local until the backend exists. |
| Device admin (EA's model: uninstall needs admin turned off first, logged) | 🧪 | **The default protection.** One tap, no adb; requests no policies, so Android's consent screen lists none. Home row "Uninstall protection". |
| ~~Device owner (managed mode)~~ | removed | Removed 2026-11-24 (D-028): needed adb and a phone with no accounts; nobody would set it up. An old install can leave it from the Uninstall protection screen. |

### Partner side
The server is live (2026-11-23). Enrollment, upload and email alerts have run with a real phone; the rest has only run in tests and curl.

| Feature | Status | Notes |
|---|---|---|
| Server (Kotlin/Ktor, flat files): signed ingest, heartbeat, invites, partner pages, alerts, weekly report, pairing mailboxes, admin page | 🧪 | **2026-11-25: D-035 deployed (every phone enrolled, invite-as-credential, signed mailbox creation, app partners recorded with their own report link, admin sign-in = emailed code or authenticator); report page reorganised.** Delivery: D-033 (Docker or tarball + `install.sh`). History: **deployed 2026-11-23 at `https://ff.flyboybyte.com`; first phone enrolled, uploaded and triggered a real alert email the same day** (flyboybyte VPS, user `ffbackend`, nginx + Let's Encrypt; `docs/plans/04-backend-deploy.md`). Checked with curl only: no phone has enrolled yet. Hardened after an independent audit and a re-audit (quotas, partner double opt-in with an email-only token, CSP/escaping, range-checked records, optional enrollment code, server-wide email caps). 31 server tests in CI. Not done: off-box backups, key attestation at enroll, a push alert when the server is full (it logs a warning). Compiles the phone's `dns/` code so alert rules are shared. |
| Phone upload + heartbeat (WorkManager, 15 min, hardware-backed P-256 key, hash chain, cursor resync) | 📱 | `sync/SyncWorker`. Starts from "now" at enrollment, not old history. |
| Email partners: the invite link opened in a browser → `/join` → confirm email → private report page (summary, Today / 7 days, tabs, sortable tables) | 📱 | Partners are double opt-in: nothing but the confirmation email goes to an address until its owner clicks Confirm. The confirm token exists only in that email (never in a redirect), so the joiner can't confirm for the owner (found by the re-audit, fixed 2026-11-23). |
| Instant alerts (filter off/revoked, a11y/usage access off, log cleared, records missing, partner removed, health problems, silence) | 📱 (filter-off only) | One rule: `Digest.alertText`. **2026-11-23, Pixel 9 + `ff.flyboybyte.com`: turned the VPN off from Android's own settings without touching the app; the confirmed partner got a "Filter turned off" email.** The other alert kinds, the silence alert and the weekly report have not been seen on a phone. Not yet measured: how long the email took, and whether it arrived before the app was opened. |
| Weekly report email | 🧪 | |
| SMS alerts (Twilio/Telnyx, opt-in, code-verified, throttled) | 🧪 | Provider account + number needed. |
| App partners (D-035): the invite pasted into *Join with an invite* enrolls the phone, records the pair on the server and opens the same server report as email; alerts and unlock codes still go through the pairing mailbox | 📱 (reports) | **2026-11-25: second phone joined and shows the main phone's reports.** Alerts to the app partner poll every 15 min; silence is flagged after 14 h. `sync/Accountability.join`, `PartnerReportActivity`. |
| Setup guide (`SetupActivity`, Settings → Set up accountability): status of every optional layer, recommended vs optional, measured cost, link to the screen that owns it | 🧪 | 2026-11-23: a checklist that changes nothing itself. Never opened on a phone. Release check for "all optional layers off": `tools/check_light_mode.py` (not yet run on a real light-mode export). |
| Group accountability screen and sync (`GroupActivity`, `GroupSync`, `GroupStore`): start/join by invite, approve by fingerprint, remove, partner-only role, narrow who sees me, leave | 🧪 | 2026-11-23: builds, all JVM tests green, **never opened on a phone and never run against the server**. Reached from Partners & reports. Needs a server address; with no group nothing changes. Known limits (server keeps 300 messages / 30 days of group history, one group per phone): `docs/plans/05-group-and-release.md`. |
| Group join/approval protocol (`dns/GroupProtocol`): signed, replayable log in one blind mailbox; members approve each other by a 12-digit fingerprint; pairing secrets handed over sealed to one member's key; narrowing/leaving revokes pairings | 🧪 | 2026-11-23: pure protocol, 13 JVM tests that simulate 4 phones (real signing, sealing and ECIES over an in-memory mailbox): all 12 pairings made with matching codes, stranger gets nothing, forged leave/remove and stolen-id hellos dropped, cap and close enforced. **No storage, sync worker or UI yet; never run on a phone.** |
| Group accountability rules (`dns/Group`): 2 to 4 members, filtered or partner-only, everyone sees every filtered member by default (narrowable), any viewer can unlock, every change tells the group, loosening changes are flagged for the friction guard | 🧪 | Added 2026-11-23: **pure rules only, 13 JVM tests. No join protocol, storage or UI yet.** Plan and build order: `docs/plans/05-group-and-release.md`. |
| Partner unlock codes (partner PIN without handing over a PIN) | 🧪 | Rotating 8-digit code in the partner's app, verified offline. |
| Removing a partner is guarded, logged, and tells them | 🧪 | |
| Exclude apps from reports | ❌ | EA has it. |
| Screenshot to partner, owner-sent and blurred (level B, D-025) | 🧪 | Review → "Send blurred to partners…"; ≤ 48 px thumbnail, one blur (was 24 px until 2026-11-24, unreleased), sealed. Automatic sending of flagged shots (level C) since 2.21 (D-037), after a 10-minute note window. |

## What's weak (read before trusting anything)

1. **Only part of the partner loop has run for real.** Email partners, instant alerts and an app partner's report work
   against the live server; app-partner alerts, unlock codes and groups have never run on two phones.
2. **Most features are 🧪, not 📱.** The relay looked fine in tests twice and failed on the phone
   twice. Assume any 🧪 item can fail the same way.
3. **Silence detection is measured on the charger only.** The 2026-11-25 export: check-ins every 15 min all night,
   longest gap 21 min, but the phone was charging overnight and the longest unplugged screen-off stretch was 42 min, so
   deep Doze is untested. The server window is now 60 min (branch `silence-alerts`, not yet deployed). Next: an
   unplugged-night export; if gaps grow, a longer window at night.
4. **The AI's accuracy is unmeasured.** The model author claims ~92% on their own data; our only real flag so far was a false positive (a photo with no nudity → `porn` 0.82). It only flags, never blocks. Before a partner receives AI flags, add persistence/combined-signal rules (`docs/research/ai-detection.md`).
5. **Always-on VPN is easy to lose.** Turning the VPN off and on from the system switch switched Always-on off (2026-11-20 export). The integrity trail records it. **Since 2026-11-23 (unreleased, 🧪)** the app also reminds the owner when the VPN starts with Always-on off: one notification per 12 h that opens VPN settings (`dns/AlwaysOnNudge`, diag counter `always_on_nudge`).

## Open decisions

Settled since this table was first written: hosting (Logan's VPS, live), domain + HTTPS (`ff.flyboybyte.com`), privacy
tier (end-to-end sealed reports, D-023), licence (AGPL-3.0 since 2026-11-25, D-038; MIT before, D-015), landing on `main` (D-017/D-022), going public (D-027), about/privacy pages on GitHub Pages (2026-11-24).

| Decision | Options / recommendation |
|---|---|
| SMS provider + monthly budget | Twilio / Telnyx / Vonage, behind one `Notifier` interface |
| Log retention after upload | Keep N days locally vs delete on acknowledgement |
| Partner login | Option B in `docs/plans/09-partner-login.md`, scoped, not built |

## Next steps, in order

The ordered list is `docs/HANDOFF.md` → Next; each piece of work is a plan in `docs/plans/`. (The old roadmap is in `docs/archive/`.)
Short version (2026-11-25, agreed with Logan: "the filter is pretty good; needs lots of work on accountability
mechanics and networking"):

1. **Accountability networking and mechanics** (what Logan means by "networking"): how phones, partners and the server
   reach each other. Report freshness (slices about hourly), alerts to app partners (15 min polling, 14 h silence),
   moving alerts and unlock codes onto the server route the report now uses, push instead of polling.
2. **Internet DNS:** solid in daily use per Logan; the 2026-11-24 export still showed 8.9% forward failures and a slow
   worst case. Unknown: gaming and latency-sensitive apps. Check from a normal day's export on 2.20.
3. Field-test the 2.16 to 2.20 screens and groups on two phones; battery from the same export.
4. Retune the AI flag rules only from `rev` answers on the Review screen.
5. Then: report exclusions, automatic blurred screenshots (level C).
6. ~~Release 2.13~~ · ~~go public~~ · ~~self-update~~ · ~~block triage~~ · ~~redesign~~ · ~~simpler accounts (D-035)~~
   · ~~one report for every partner~~ (2.13 to 2.20, 2026-11-24/25).

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
