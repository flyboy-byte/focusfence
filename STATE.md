# State of the project

> **2.10.0 (code 31, 2026-11-23)** = 2.9.2 plus: DNS resolvers race in Automatic mode (`dns/Hedged`, 0.6 s, bounded pool) after a morning of 3-7 s p95 lookups, `net` diag record (link, roaming, signal), Setup screen as cards, About row (button hidden if no URL; pages served at `https://ff.flyboybyte.com/about/`), screenshot wording corrected (images never leave the phone), a paired partner's code can skip the strict wait (wait always offered), two unguarded API-29 device-owner calls fixed (lint). Tests + lint green; 🧪 none of it seen on a phone yet.

> **Blocked screen (2026-11-23, on main after 2.10.0):** a site blocked by a user rule now opens a full-screen "Blocked by Focus Fence" page (`BlockedActivity`) over the browser, once the accessibility service confirms the address bar shows that site; without the service, a quiet per-domain notification instead. Section rules use the same screen instead of the toast. Switch: Settings → Filtering → "Show a blocked screen" (on by default). Logic in `dns/BlockScreen` with tests. 🧪 not yet seen on a phone.

> Screenshots never leave the phone today (checked 2026-11-23): only scores/labels reach the log and server. A flagged image is kept on the phone only if a partner key is pasted at Monitoring. Upload to partners is unbuilt; privacy page and strings now say so.

_Last updated: 2026-11-23. Update this file in the same commit as any change that moves an item
below. It is the single place to answer "where are we?" — keep it honest, short, and dated._

## In one paragraph

This branch turns Focus Fence (a DNS-only website blocker) into **Focus Fence AC**, a
self-accountability app benchmarked against **Ever Accountable** (EA) on Android. The phone side
is mostly built: filtering with curated bundles, an activity log for accountability, a diagnostics
log for "is it working", filter-health detection, settings friction, device admin / device owner
modes, and opt-in text and screenshot capture. The **partner loop is now built end to end in
code** — email/web partners via a server, and app-to-app pairing through a blind mailbox — and
passes its tests, but **no server is deployed yet**, so none of it has run for real. Deploying the
server and trying it on two phones is the next step.

## Where the code is

| | |
|---|---|
| Repo | `sidewinderzz/focusfence` (owner: sidewinderzz; contributor: flyboy-byte) |
| Working branch | `claude/logging-and-accountability-plan` — all of this work. Not merged anywhere. |
| Based on | `claude/android-vpn-website-blocker-1otcbn` (repo default branch), 0 commits behind |
| App id | `app.focusfence.accountable` ("Focus Fence AC") — installs **alongside** the original `app.focusfence` |
| Signing cert SHA-256 | `2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671` |
| Tests | 180 app JVM unit tests + 9 server tests (Ktor test host); both CI jobs green |
| Latest release | Tagged `v2.7.2-accountable` (full release, stable key); the Pixel 9 ran 2.7.2 at the 2026-11-23 export. **2.9.2 (code 30, tagged 2026-11-23): the filter now restarts itself after an app update or a reboot if you had it on (`vpn/AutoStartReceiver`, `VpnWanted`; before, only Always-on VPN did that, and after several past updates it stayed off until the app was opened; 🧪 not yet tried on a phone), and opening the app does the same. 2.9.1 (code 29, tagged 2026-11-23): no more "Filter turned off"-style email on an app update (the `vpn_metered` flip is no longer an alert; server already deployed with it), "About and privacy" in Settings. Before it, the branch was at `2.9.0-accountable` (code 28), tagged `v2.9.0-accountable` 2026-11-23: group accountability screen (start/join/approve/leave, partner-only role; 🧪 never run on a phone), "Set up accountability" checklist, enrollment-code field for servers that need one, and everything from 2.8.0.** 2.8.0 (code 27, signed with the stable key, cert hash matches) was: AI flag policy (`dns/FlagPolicy`), Always-on VPN reminder (`dns/AlwaysOnNudge`), **Settings → Logs & diagnostics → "Save to Downloads"** (the export no longer has to go through another app; Android 10+ writes straight to Downloads, older versions use the file picker; 🧪 not yet tried on the phone), and the server address can point at `https://ff.flyboybyte.com`. Before that, 2.7.2 (code 26): **strict-mode unlock window 5 → 15 min, plus a notification when the wait ends** (the owner kept missing the window; untested on a phone). Before that, 2.7.1 (code 25): **owner screenshot review** (full per-label scores on each card, thresholds down to 10%) (Monitoring → Review screenshots: risky captures at/above your % kept locally, with Not a problem / Real hit) + VPN exclusions are friction-free for games/music/maps and guarded (strict-mode delay) for browsers, social, video, photos and anything uncategorised; address bar not recorded while typing; research notes in `docs/research/`. History: 2.2 partner loop in code · 2.3 visual refresh, all-apps watching · 2.4 screenshots working · 2.5 accessibility event diet · 2.6.0 AI skip on unchanged screens, risky words, adult/gambling Report, battery/restricted-settings guidance, Stats chart · 2.6.1 VPN not metered. |

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
| User allowlist | 🧪 | |
| DoH-bypass guards | 📱 | Inherited. |
| App blocking (accessibility bounce) | 📱 | Inherited. |
| App suspension (Device Owner) | 🧪 | |

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
| AI flag policy (`dns/FlagPolicy`): a raw hit is reported/kept for a partner only if score ≥ 0.90, or a 2nd hit in the same app within 3 min, or ≥ 0.75 with adult words on screen in that app | 🧪 | Added 2026-11-23, 7 JVM tests, not on a phone. Untuned guesses; owner review screen unaffected. Diag counters `flag_strong/persistent/corroborated/held`. Don't retune without recording why (`docs/research/ai-detection.md`). |
| On-device AI flagging (optional, off by default) | 📱 | **First real flag 2026-11-20 was a false positive:** a friend's shirtless selfie scored `porn` 0.82. 236 screenshots scored so far, avg 91 ms, model load ~2 s; unchanged screens skip the AI (23 of 89 on 2.6.1). Options to cut false alarms without battery cost: `docs/research/ai-detection.md`. 2026-11-19: 41 screenshots scored, avg 100 ms, all neutral/drawings, model load 1.4 s. Bundled: GantMan MobileNet v2 NSFW (MIT), float16, 12 MB, all ops v1–v3 for the TFLite 2.14 runtime. Matched the original model on 35 synthetic inputs (same top label, ≤0.03 diff); not tested on real content or on a phone. Battery cost logged: diag `clf`, `clf_ms`, `clf_load_ms`, shown in the diagnostics report. |

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
| Device owner: always-on VPN (no lockdown), VPN/Private DNS settings lock, Private DNS fallback, strategy switch, network logging | 🧪 | Needs adb and all accounts removed while provisioning. `specs/device-owner.md`. |

### Partner side
The server is deployed (2026-11-23) but no phone has used it yet: none of this has run outside tests and curl.

| Feature | Status | Notes |
|---|---|---|
| Server (Kotlin/Ktor, flat files): signed ingest, heartbeat, invites, partner pages, alerts, weekly report, blind mailbox | 🧪 | **Deployed 2026-11-23 at `https://ff.flyboybyte.com`; first phone enrolled, uploaded and triggered a real alert email the same day** (flyboybyte VPS, user `ffbackend`, nginx + Let's Encrypt; `docs/plans/04-backend-deploy.md`). Checked with curl only: no phone has enrolled yet. Hardened after an independent audit and a re-audit (quotas, partner double opt-in with an email-only token, CSP/escaping, range-checked records, optional enrollment code, server-wide email caps). 31 server tests in CI. Not done: off-box backups, key attestation at enroll, a push alert when the server is full (it logs a warning). Compiles the phone's `dns/` code so alert rules are shared. |
| Phone upload + heartbeat (WorkManager, 15 min, hardware-backed P-256 key, hash chain, cursor resync) | 📱 | `sync/SyncWorker`. Starts from "now" at enrollment, not old history. |
| Email partners: one-time invite code → `/join` → confirm email → private report page | 📱 | Partners are double opt-in: nothing but the confirmation email goes to an address until its owner clicks Confirm. The confirm token exists only in that email (never in a redirect), so the joiner can't confirm for the owner (found by the re-audit, fixed 2026-11-23). |
| Instant alerts (filter off/revoked, a11y/usage access off, log cleared, records missing, partner removed, health problems, silence) | 📱 (filter-off only) | One rule: `Digest.alertText`. **2026-11-23, Pixel 9 + `ff.flyboybyte.com`: turned the VPN off from Android's own settings without touching the app; the confirmed partner got a "Filter turned off" email.** The other alert kinds, the silence alert and the weekly report have not been seen on a phone. Not yet measured: how long the email took, and whether it arrived before the app was opened. |
| Weekly report email | 🧪 | |
| SMS alerts (Twilio/Telnyx, opt-in, code-verified, throttled) | 🧪 | Provider account + number needed. |
| App-to-app pairing by shared key; blind mailbox; partner app view | 🧪 | `dns/Pairing`, `AccountabilityActivity`, `PartnerReportActivity`. Both phones must use the same server. |
| Setup guide (`SetupActivity`, Settings → Set up accountability): status of every optional layer, recommended vs optional, measured cost, link to the screen that owns it | 🧪 | 2026-11-23: a checklist that changes nothing itself. Never opened on a phone. Release check for "all optional layers off": `tools/check_light_mode.py` (not yet run on a real light-mode export). |
| Group accountability screen and sync (`GroupActivity`, `GroupSync`, `GroupStore`): start/join by invite, approve by fingerprint, remove, partner-only role, narrow who sees me, leave | 🧪 | 2026-11-23: builds, all JVM tests green, **never opened on a phone and never run against the server**. Reached from Partners & reports. Needs a server address; with no group nothing changes. Known limits (server keeps 300 messages / 30 days of group history, one group per phone): `docs/plans/05-group-and-release.md`. |
| Group join/approval protocol (`dns/GroupProtocol`): signed, replayable log in one blind mailbox; members approve each other by a 12-digit fingerprint; pairing secrets handed over sealed to one member's key; narrowing/leaving revokes pairings | 🧪 | 2026-11-23: pure protocol, 13 JVM tests that simulate 4 phones (real signing, sealing and ECIES over an in-memory mailbox): all 12 pairings made with matching codes, stranger gets nothing, forged leave/remove and stolen-id hellos dropped, cap and close enforced. **No storage, sync worker or UI yet; never run on a phone.** |
| Group accountability rules (`dns/Group`): 2 to 4 members, filtered or partner-only, everyone sees every filtered member by default (narrowable), any viewer can unlock, every change tells the group, loosening changes are flagged for the friction guard | 🧪 | Added 2026-11-23: **pure rules only, 13 JVM tests. No join protocol, storage or UI yet.** Plan and build order: `docs/plans/05-group-and-release.md`. |
| Partner unlock codes (partner PIN without handing over a PIN) | 🧪 | Rotating 8-digit code in the partner's app, verified offline. |
| Removing a partner is guarded, logged, and tells them | 🧪 | |
| Exclude apps from reports | ❌ | EA has it. |
| Screenshot upload to partner | ❌ | Server endpoint exists; phone doesn't send yet. |

## What's weak (read before trusting anything)

1. **The partner loop has never run for real.** It passes unit and server tests, but no server is
   deployed and no two phones have paired. Expect first-contact bugs.
2. **Most features are 🧪, not 📱.** The relay looked fine in tests twice and failed on the phone
   twice. Assume any 🧪 item can fail the same way.
3. **Silence detection depends on timing guesses.** The server flags a phone after 4 h without a
   check-in; the partner app after 14 h without a message. Doze can delay background work for
   hours overnight, so these may need tuning against real behaviour (false alarms vs slow alarms).
4. **The AI's accuracy is unmeasured.** The model author claims ~92% on their own data; our only real flag so far was a false positive (shirtless man → `porn` 0.82). It only flags, never blocks. Before a partner receives AI flags, add persistence/combined-signal rules (`docs/research/ai-detection.md`).
5. **Always-on VPN is easy to lose.** Turning the VPN off and on from the system switch switched Always-on off (2026-11-20 export). The integrity trail records it. **Since 2026-11-23 (unreleased, 🧪)** the app also reminds the owner when the VPN starts with Always-on off: one notification per 12 h that opens VPN settings (`dns/AlwaysOnNudge`, diag counter `always_on_nudge`).
6. **One big unreviewed branch** on someone else's repo. No `LICENSE` file in the repo.

## Open decisions (owner / maintainer)

**2026-11-23, landing on `main` and group accountability: see `docs/plans/05-group-and-release.md`.** `main` is releases-only and
`shareddev` is a 29-commit parallel line (25 conflicting files with this branch); D-010/D-012/D-016 on `main` reject what this
branch does, so a D-017 is needed first. Group accountability (up to 4 phones, per-member roles) is designed there, not built.

| Decision | Options / recommendation |
|---|---|
| Backend hosting | Logan's VPS (Resend already there) — recommended for the prototype. **Blocks everything on the partner side.** |
| Domain + HTTPS | Needed: the app only talks to `https://` servers |
| SMS provider + monthly budget | Twilio / Telnyx / Vonage, behind one `Notifier` interface |
| Privacy tier | **Decided 2026-11-23 (owner): server-readable records, personal use; "less privacy now, harden later if we open it up."** Screenshots stay end-to-end encrypted to the partner key. Revisit before anyone but the owner uses the server. |
| Log retention after upload | Keep N days locally vs delete on acknowledgement |
| Default protection tier | Recommend device admin as default (EA parity), device owner as opt-in |
| License | MPL-2.0 or Apache-2.0 suggested (`docs/plans/00-roadmap.md` Tier 0) |
| Merge plan | Whether/how this branch lands in the maintainer's repo |

## Next steps, in order

0. Use the review screen for a few days: the `rev` answers are the first real false-alarm rate (`docs/research/ai-detection.md` option C is now built).
1. Deploy `server/` on the VPS behind HTTPS (`server/README.md`), set the server address in the
   app, invite an email partner, pair a second phone, send an export after a day.
2. ~~AI: persistence + combined-signal rules~~ — built 2026-11-23 (unreleased, `dns/FlagPolicy`); the "Not a problem" button shipped in 2.7.0. Retune only from `rev` answers.
3. ~~Prompt to re-enable Always-on VPN~~ — built 2026-11-23 (unreleased); confirm it fires on the Pixel.
4. UI: Home hero state, Settings grouped into cards, Monitoring "Recent flags"
   (`docs/research/ui-design.md`).
5. Then: report exclusions, screenshot upload to partner.

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
