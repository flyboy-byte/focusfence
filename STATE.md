# State of the project

_Last updated: 2026-11-17. Update this file in the same commit as any change that moves an item
below. It is the single place to answer "where are we?" — keep it honest, short, and dated._

## In one paragraph

This branch turns Focus Fence (a DNS-only website blocker) into **Focus Fence AC**, a
self-accountability app benchmarked against **Ever Accountable** (EA) on Android. The phone side
is mostly built: filtering with curated bundles, an activity log for accountability, a diagnostics
log for "is it working", filter-health detection, settings friction, device admin / device owner
modes, and opt-in text and screenshot capture. **Nothing reaches an accountability partner yet**:
the backend is a prototype and the phone doesn't upload. That loop is EA's core feature and the
next major piece of work.

## Where the code is

| | |
|---|---|
| Repo | `sidewinderzz/focusfence` (owner: sidewinderzz; contributor: flyboy-byte) |
| Working branch | `claude/logging-and-accountability-plan` — all of this work. Not merged anywhere. |
| Based on | `claude/android-vpn-website-blocker-1otcbn` (repo default branch), 0 commits behind |
| App id | `app.focusfence.accountable` ("Focus Fence AC") — installs **alongside** the original `app.focusfence` |
| Latest release | `v2.1.3-accountable` (versionCode 9), GitHub pre-release, signed with the stable key |
| Signing cert SHA-256 | `2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671` |
| Tests | 106 JVM unit tests (21 classes in `app/src/test/`); CI green |

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
| DNS-only VPN filter, relay via network DNS → DoH fallback | 📱 | v2.1.3 runs with working internet. Fixed two broken relays (see Incidents). Blocking itself not re-checked on 2.1.3. |
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
| In-app text capture | 🧪 | Opt-in, `MonitoringActivity`. |
| Screenshots: sparse capture, classifier, flagged-only encrypted retention, always-on indicator | 🧪 | **No model bundled** — classifier is a stub until one is supplied. |

### Tamper resistance and friction
| Feature | Status | Notes |
|---|---|---|
| Friction guards per action (None / Delay / Own PIN / Partner PIN) | 🧪 | `specs/friction-tiers.md`. Partner PIN is local until the backend exists. |
| Device admin (EA's model: uninstall needs admin turned off first, logged) | 🧪 | One tap, no adb. Added 2026-11-17. |
| Device owner: always-on VPN (no lockdown), VPN/Private DNS settings lock, Private DNS fallback, strategy switch, network logging | 🧪 | Needs adb and all accounts removed while provisioning. `specs/device-owner.md`. |

### Partner side
| Feature | Status | Notes |
|---|---|---|
| Backend (enroll, signed ingest, heartbeat, Resend email, encrypted screenshots) | 📝 | `server/`, Kotlin/Ktor, flat-file store. |
| Phone → server upload client + heartbeat | ❌ | **Next.** |
| Instant alerts (protection off, stale heartbeat, unlink) | ❌ | Email first, SMS later. |
| Weekly report email | ❌ | EA parity. |
| Partner linking by one-time invite code | ❌ | Designed: `specs/accountability-architecture.md` §6. |
| SMS alerts via a provider | ❌ | Designed (same section). |
| Exclude apps from reports | ❌ | EA has it. |

## What's weak (read before trusting anything)

1. **No partner loop.** Everything stays on the phone. Without it, this is a well-instrumented
   filter, not an accountability app.
2. **Most features are 🧪, not 📱.** The relay looked fine in tests twice and failed on the phone
   twice. Assume any 🧪 item can fail the same way.
3. **Silent removal.** Without device admin/owner, uninstall or "Clear storage" wipes the app and
   its log. Only a server-side heartbeat can catch that, and it doesn't exist yet.
4. **No screenshot model.** The pipeline runs, but nothing is flagged until a model is bundled.
5. **One big unreviewed branch** on someone else's repo. No `LICENSE` file in the repo.

## Open decisions (owner / maintainer)

| Decision | Options / recommendation |
|---|---|
| Backend hosting | Logan's VPS (Resend already there) — recommended for the prototype |
| Domain | For the partner page and invite links |
| SMS provider + monthly budget | Twilio / Telnyx / Vonage, behind one `Notifier` interface |
| Privacy tier | Recommend P3: server-readable records, end-to-end-encrypted screenshots |
| Log retention after upload | Keep N days locally vs delete on acknowledgement |
| Default protection tier | Recommend device admin as default (EA parity), device owner as opt-in |
| License | MPL-2.0 or Apache-2.0 suggested (`docs/plans/00-roadmap.md` Tier 0) |
| Merge plan | Whether/how this branch lands in the maintainer's repo |

## Next steps, in order

1. Phone: confirm blocking + health row on v2.1.3; send a diagnostics export after a day of use.
2. Settle hosting / domain / privacy tier.
3. Upload client + heartbeat (WorkManager, signed batches, matching `server/`).
4. Email alerts → weekly report → invite-code linking → SMS.

## Incidents (what went wrong, so it isn't repeated)

| When | What | Cause | Fix |
|---|---|---|---|
| pre-branch | Service woke thousands of times a day | Blocked queries answered NXDOMAIN with no SOA, so clients re-asked | Cacheable `0.0.0.0`/`::` with TTLs |
| 2026-11-16 | First-run CPU spike | Category reload storm on bundle download | Coalesced single-thread reload |
| 2026-11-16 | 75% of relays failing, slow browsing | Plain UDP to 1.1.1.1/8.8.8.8 unreliable on the test network | (superseded) |
| v2.1.1 | No DNS at all | DoH-only relay; also `disconnect()` killed keep-alive | v2.1.3: network's own resolver first, DoH fallback |
| v2.1.x | No internet while VPN on | Android lockdown ("Block connections without VPN") drops non-tunnel traffic | Health state `lockdown` + alert; never enable lockdown |
| v2.1.0 | APK wouldn't install | Release was unsigned | CI signs; stable key via repo secrets |
