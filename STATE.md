# State of the project

_Last updated: 2026-11-20. Update this file in the same commit as any change that moves an item
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
| Tests | 168 app JVM unit tests + 9 server tests (Ktor test host); both CI jobs green |
| Latest release | `v2.6.1-accountable` (full release, stable key; installed on the Pixel 9 over 2.4.1). Branch is at `2.7.2-accountable` (code 26): **strict-mode unlock window 5 → 15 min, plus a notification when the wait ends** (the owner kept missing the window; untested on a phone). Before that, 2.7.1 (code 25): **owner screenshot review** (full per-label scores on each card, thresholds down to 10%) (Monitoring → Review screenshots: risky captures at/above your % kept locally, with Not a problem / Real hit) + VPN exclusions are friction-free for games/music/maps and guarded (strict-mode delay) for browsers, social, video, photos and anything uncategorised; address bar not recorded while typing; research notes in `docs/research/`. History: 2.2 partner loop in code · 2.3 visual refresh, all-apps watching · 2.4 screenshots working · 2.5 accessibility event diet · 2.6.0 AI skip on unchanged screens, risky words, adult/gambling Report, battery/restricted-settings guidance, Stats chart · 2.6.1 VPN not metered. |

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
| On-device AI flagging (optional, off by default) | 📱 | **First real flag 2026-11-20 was a false positive:** a friend's shirtless selfie scored `porn` 0.82. 236 screenshots scored so far, avg 91 ms, model load ~2 s; unchanged screens skip the AI (23 of 89 on 2.6.1). Options to cut false alarms without battery cost: `docs/research/ai-detection.md`. 2026-11-19: 41 screenshots scored, avg 100 ms, all neutral/drawings, model load 1.4 s. Bundled: GantMan MobileNet v2 NSFW (MIT), float16, 12 MB, all ops v1–v3 for the TFLite 2.14 runtime. Matched the original model on 35 synthetic inputs (same top label, ≤0.03 diff); not tested on real content or on a phone. Battery cost logged: diag `clf`, `clf_ms`, `clf_load_ms`, shown in the diagnostics report. |

### Battery (Pixel 9, Focus Fence's own CPU; whole-phone %/h is too noisy to compare)
| Version | Screen on | Screen off | Notes |
|---|---|---|---|
| 2.4.1 (36 h) | 87 s CPU/h; ~28,500 accessibility events/h | 2.7 s/h | Accessibility event firehose was ~60% of it |
| 2.6.1 (14 h) | **38 s CPU/h; ~550 events/h** | 2.3 s/h | Biggest costs now: screenshots + AI ~7.6 s/h, DNS relay ~4.6 s/h, binder/GC from screen reads |

### Tamper resistance and friction
| Feature | Status | Notes |
|---|---|---|
| Friction guards per action (None / Delay / Own PIN / Partner PIN) | 🧪 | `specs/friction-tiers.md`. Partner PIN is local until the backend exists. |
| Device admin (EA's model: uninstall needs admin turned off first, logged) | 🧪 | **The default protection.** One tap, no adb; requests no policies, so Android's consent screen lists none. Home row "Uninstall protection". |
| Device owner: always-on VPN (no lockdown), VPN/Private DNS settings lock, Private DNS fallback, strategy switch, network logging | 🧪 | Needs adb and all accounts removed while provisioning. `specs/device-owner.md`. |

### Partner side
Everything here needs a deployed server (`server/README.md`); none of it has run outside tests.

| Feature | Status | Notes |
|---|---|---|
| Server (Kotlin/Ktor, flat files): signed ingest, heartbeat, invites, partner pages, alerts, weekly report, blind mailbox | 🧪 | 9 server tests in CI. Not deployed. Compiles the phone's `dns/` code so alert rules are shared. |
| Phone upload + heartbeat (WorkManager, 15 min, hardware-backed P-256 key, hash chain, cursor resync) | 🧪 | `sync/SyncWorker`. Starts from "now" at enrollment, not old history. |
| Email partners: one-time invite code → `/join` → private report page | 🧪 | |
| Instant alerts (filter off/revoked, a11y/usage access off, log cleared, records missing, partner removed, health problems, silence) | 🧪 | One rule: `Digest.alertText`. |
| Weekly report email | 🧪 | |
| SMS alerts (Twilio/Telnyx, opt-in, code-verified, throttled) | 🧪 | Provider account + number needed. |
| App-to-app pairing by shared key; blind mailbox; partner app view | 🧪 | `dns/Pairing`, `AccountabilityActivity`, `PartnerReportActivity`. Both phones must use the same server. |
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
5. **Always-on VPN is easy to lose.** Turning the VPN off and on from the system switched Always-on off (2026-11-20 export). The integrity trail records it, but the app doesn't prompt to turn it back on yet.
6. **One big unreviewed branch** on someone else's repo. No `LICENSE` file in the repo.

## Open decisions (owner / maintainer)

| Decision | Options / recommendation |
|---|---|
| Backend hosting | Logan's VPS (Resend already there) — recommended for the prototype. **Blocks everything on the partner side.** |
| Domain + HTTPS | Needed: the app only talks to `https://` servers |
| SMS provider + monthly budget | Twilio / Telnyx / Vonage, behind one `Notifier` interface |
| Privacy tier | Recommend P3: server-readable records, end-to-end-encrypted screenshots |
| Log retention after upload | Keep N days locally vs delete on acknowledgement |
| Default protection tier | Recommend device admin as default (EA parity), device owner as opt-in |
| License | MPL-2.0 or Apache-2.0 suggested (`docs/plans/00-roadmap.md` Tier 0) |
| Merge plan | Whether/how this branch lands in the maintainer's repo |

## Next steps, in order

0. Use the review screen for a few days: the `rev` answers are the first real false-alarm rate (`docs/research/ai-detection.md` option C is now built).
1. Deploy `server/` on the VPS behind HTTPS (`server/README.md`), set the server address in the
   app, invite an email partner, pair a second phone, send an export after a day.
2. AI: persistence + combined-signal rules and a "Not a problem" button before partners see
   flags (`docs/research/ai-detection.md` A–C).
3. Prompt to re-enable Always-on VPN when the integrity trail shows it went off.
4. UI: Home hero state, Settings grouped into cards, Monitoring "Recent flags"
   (`docs/research/ui-design.md`).
5. Then: report exclusions, screenshot upload to partner.

## Incidents (what went wrong, so it isn't repeated)

| When | What | Cause | Fix |
|---|---|---|---|
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
