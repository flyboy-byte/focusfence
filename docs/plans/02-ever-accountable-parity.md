# Ever Accountable parity: gap matrix and build order

The comparison target is **Ever Accountable on Android** (EA). This document lists what EA
does, what Focus Fence (FF) does as of this branch, and the build order that closes the gap.

The priority is **detection and reporting first, blocking second**. EA is a reporting product
with optional filtering. FF keeps its blocking as a strength, but parity is measured on the
reporting side.

Statuses: ✅ exists · 🟡 partial / local only · ❌ missing · ➕ FF goes beyond EA.

## Feature matrix

| Capability | Ever Accountable (Android) | Focus Fence now | Status | Gap to close |
|---|---|---|---|---|
| **Websites visited** | Reported. Read from browsers via accessibility. | DNS hostnames for every lookup (all apps). Browser host+path when "Record browser addresses" is on. | 🟡 | Local only; needs sync + report. Firefox/Chromium URL-bar IDs need ongoing maintenance (diag `url_miss` measures it). |
| **Content inside apps** ("snippets of text") | Reported from many popular apps via accessibility. | Not captured. | ❌ | Accessibility text capture for chosen apps. Highest battery/privacy cost item: needs per-app adapters, sampling, and measurement first. |
| **Time spent in all apps** | Reported (Usage Stats permission). | Foreground timeline from UsageStats in the activity log. | 🟡 | Local only. Summarise into per-app daily totals for reports. |
| **Screenshots** | Optional, periodic, adjustable resolution, optional blur. | None. | ❌ | Accessibility screenshot API (Android 11+) or MediaProjection, plus on-device classifier. Retention mode choice (`specs/accountability-architecture.md` §Screenshots). |
| **Concerning-content flagging** | On-device AI flags; incident reports. | Category of a *domain* only (ad list, user rules). | ❌ | Domain category lists (adult etc.) as *labels* (detect, optionally block); later image classifier. |
| **Weekly report to partner** | Yes; partners chosen by user. | Local diagnostics report + export only. | ❌ | Backend, partner accounts, scheduled email report. |
| **Alerts: protection disabled / uninstall** | Uninstall alerts; alert when filtering disabled; partners notified when sharing stops. | Integrity trail logs VPN/a11y/Usage-access/Private-DNS changes, process restarts, reboots, log clears, setting changes, locally. | 🟡 | Needs delivery: server heartbeat (silence = alert) + immediate upload of integrity events. Uninstall can only be detected server-side (silence) or prevented (Device Admin/Owner). |
| **Settings lock** (StrongHold) | PIN sent to someone else, local PIN, or timer delay. | Strict mode = timer delay (10 min + 5 min window). Covers turning off logging and clearing logs on this branch. | 🟡 | Add partner-held PIN and own-PIN unlock to the same `StrictLock` state machine. |
| **Uninstall resistance** | Device Administrator. | None. | ❌ | Device Admin (cheap, weak) or Device Owner (strong; see managed mode). |
| **App exclusions from reports** | User can exclude apps / whitelist apps. | VPN exclusions exist (filtering); no report exclusions. | ❌ | Report-exclusion list; exclusions themselves reported to partner. |
| **Web filtering** | DNS filter for porn/malware, not customisable. | Per-domain rules, schedules, budgets, section rules, ad list, DoH guards. | ➕ | Add category sources (HaGeZi NSFW etc.) and an allowlist (`00-roadmap.md` Tier 1). |
| **App blocking** | Not a core feature. | Accessibility bounce. | ➕ | Device Owner `setPackagesSuspended` in managed mode. |
| **Self-hosting / FOSS** | No. | Possible by design. | ➕ | Keep the backend small and self-hostable. |
| **Measurement of own reliability** | Not exposed. | Diag log: battery/CPU, resolver latency & failures, a11y event volume, URL-read miss rate, log loss. | ➕ | — |

Ever Accountable details come from its store listing, Android user guide and help section, and
two independent reviews (sources at the end). Two items come from the research handoff and were
not re-verified against a live EA page: the StrongHold options and the Device Admin use. EA's
support pages refused automated fetches.

## Build order

Each phase leaves the app working. Phases 1–2 can ship to the phone without a backend. Where a
phase depends on a measurement, the gate is stated.

### Phase 0 — measure (this branch)
Activity log, diag log, integrity trail, export, analyzer. Run the plan in
[`03-measurement-plan.md`](03-measurement-plan.md) on the target phone for a few days. **Every
later "is it worth it" decision reads from this.**

### Phase 1 — local accountability record (no server)
- Per-app daily time from the UsageStats timeline; per-site daily lookups/visits.
- Domain **category labels** from imported lists (adult, gambling, social…) attached to `dns`
  records, independent of whether the category blocks. This is the "detection not blocking"
  core.
- User allowlist (prerequisite for any second list).
- A readable on-phone timeline view (today / this week), built from the same records a partner
  would get.
- Strict mode: own-PIN and partner-PIN unlock modes.

**Gate:** the timeline should answer "what did this phone do yesterday" well enough that you'd
trust it. If it can't, find out from the diag numbers why before adding a server.

### Phase 2 — in-app content (accessibility)
- Text snippets for a short list of high-value apps (browsers first, then the social apps you
  actually use). Each app gets its own adapter. Scoped event registration, same as today.
- Diag counters per adapter, covering events/hour and capture misses.

**Gate:** the measured battery cost of the widened accessibility scope (`a11y_ev`/h and
screen-on drain vs. a run without it) is acceptable to you.

### Phase 3 — backend and partner reports
- Device enrollment, Keystore device key, batched signed upload of activity records, and a
  server-side per-device sequence check (gaps = missing data, shown to the partner).
- Heartbeat. Prolonged silence becomes a partner alert. This is the *only* way to detect
  uninstall, factory reset, or "phone off for a week".
- Weekly email report (EA parity), immediate alerts for integrity events, and a minimal web
  timeline.

**Gate:** one week of real data produces a report a partner finds useful. Record volume and
upload size come from the measurement export.

### Phase 4 — screenshots and classifier
- Sparse screenshots via the Accessibility screenshot API, an on-device classifier, and
  retention of flagged images only by default. Optional E2EE so the server stores ciphertext.

**Gate:** measured inference cost per day, plus the false-positive rate on your own usage.

### Phase 5 — managed (strong) mode, optional
- Device Owner (ADB/QR provisioning, Shizuku-assisted as an option) with:
  - uninstall block
  - always-on VPN with lockdown
  - VPN/Private DNS config restriction
  - package suspension
  - network logging
- A documented recovery path is required before any of this is enabled.

**Gate:** experiment 1 and experiment 5 from the handoff, run on the target phone.

### Not before evidence
Full-TUN VPN, embedded WireGuard, and HTTPS interception stay out until the measurements show a
blind spot that DNS, UsageStats, accessibility, screenshots and Device Owner network logs can't
cover.

## Sources

- [Ever Accountable store listing](https://apps.apple.com/us/app/sunlight-browser-build-trust-with-accountability/id1074047778)
  and [Play listing mirror](https://apps.appfollow.io/android/ever-accountable-rise-above-pornography/com.everaccountable.android?country=ph):
  reports websites, content inside apps, time in all apps. Apps can be excluded, and partners
  are notified when sharing stops.
- [Android user guide](https://en.everaccountable.com/blog/android-users-guide-to-ever-accountable-for-busy-people):
  Usage Stats for app time; optional periodic screenshots with resolution setting.
- [Android help section](https://support.everaccountable.com/hc/en-us/sections/115000871251-Android):
  screenshots, filtering, app sharing/whitelisting, uninstall, troubleshooting.
- [learnofchrist.com review](https://learnofchrist.com/resources/ever-accountable): "built to report, not to block".
- [AirDroid comparison](https://www.airdroid.com/parental-insights/ever-accountable-vs-covenant-eyes/):
  Android-only DNS filtering of porn/malware, not customisable; optional unblurred screenshots.
