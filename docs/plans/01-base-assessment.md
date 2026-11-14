# Is Focus Fence the right base for an Ever Accountable–class app?

**Short answer: yes. Build on it, as an additive expansion, and sort out two non-technical
conditions first: a license, and the maintainer's agreement to the change of identity.**

The yardstick throughout is **Ever Accountable on Android**, since that is the product this will
be compared against. [`02-ever-accountable-parity.md`](02-ever-accountable-parity.md) has the
feature-by-feature comparison.

## What the question really is

Ever Accountable is, in its own reviewers' words, built to *report*, not to *block*. Its core
is detection and reporting:
- which websites were visited
- what was on screen inside apps
- time spent in every app
- optional screenshots
- weekly reports, plus alerts to a chosen partner when protection is removed

Filtering is a secondary, optional feature, and on Android it is a fixed DNS filter.

Focus Fence today is the opposite shape. It is a capable blocker with almost no reporting. So the
real question is not "does Focus Fence already do most of this?" It doesn't. The question is
whether its foundations help or get in the way of the parts that have to be built anyway.

## What carries over

| Existing piece | Why it matters for the new scope |
|---|---|
| Narrow DNS-only `VpnService` (routes only two /32 & /128 addresses) | Cheapest possible device-wide observation *and* filtering point. Every query is now a detection event (this branch). Ever Accountable's own Android filtering is DNS-based too, but not customisable; Focus Fence's per-domain rules, schedules and budgets already go further. |
| Pure-JVM `dns/` layer (now ~13 files, 59 unit tests) | Detection logic, classification, report summarisation and integrity rules can all be built and tested without a device, which matters a lot for something that has to be trustworthy. |
| Scoped accessibility service | The same service Ever Accountable relies on for "content inside apps". The narrow event-filter pattern is exactly what is needed to keep a wider monitoring scope affordable. |
| Strict mode (`StrictLock`: request → 10 min wait → 5 min window) | Equivalent to Ever Accountable's StrongHold "timer" option. Adding partner-held PIN and own-PIN modes extends the same state machine. |
| App blocking, section rules, time budgets | Beyond what Ever Accountable offers on Android. Kept as optional enforcement. |
| Small codebase (~2.9k lines of Kotlin before this branch) | Cheap to reshape. Nothing here is load-bearing in a way that would fight new subsystems. |

## What doesn't exist and must be built regardless of base

This is most of the work. It is new code whichever repo it lands in:
- the event store
- UsageStats app time (started on this branch)
- browser URL and in-app text capture
- integrity and tamper detection (started on this branch)
- screenshots and an on-device classifier
- a backend
- partner accounts, reports and alerts
- uninstall protection
- an optional Device Owner mode

The surveyed alternatives provide little of it:

- **Start from scratch.** You would rebuild the DNS filter and its tests first, and gain nothing.
- **Déchaîner** (Apache-2.0). It has Device Owner via Shizuku, a TFLite explicit-image
  classifier and Private DNS lockdown. It has no reporting, no partner model and no backend. It
  forces a family-safe Private DNS provider rather than filtering locally, ships arm64 only, and
  is a small project (about 28 stars). It's a strong **reference** for the managed-mode and
  classifier pieces. Its license is compatible with borrowing ideas, and borrowing code once
  Focus Fence has a compatible license. It is not a better base.
- **Curbox, TrackerControl** (GPL-3.0). These are useful to study. Copying code from them would
  force the whole project to GPL.

## What will have to change in Focus Fence

These are real but bounded:

1. **Storage.** Rules live in a SharedPreferences `StringSet`. That's fine for rules but wrong
   for an event history. This branch adds an append-only JSON-lines log, which suffices for
   measurement and local review. Sync and partner reports will want SQLite/Room with per-event
   upload state (see [`../specs/accountability-architecture.md`](../specs/accountability-architecture.md)).
2. **Identity.** README, onboarding and help say "nothing leaves your phone". An accountability
   product deliberately sends a record to a chosen partner. The copy and the privacy model need
   rewriting when sync exists, not before. This branch only corrects the claims that were already
   inaccurate.
3. **Platform targets.** `targetSdk 34` cannot ship Play updates after Nov 1, 2026 (API 36
   required). Accessibility use for monitoring also needs Play's prominent-disclosure flow.
   Sideloaded builds are unaffected.
4. **Single process, singletons.** This works today and the new `EventLog`/`Monitor` follow the
   same pattern. Revisit only if a separate `:monitor` process becomes necessary for survival
   under OEM battery managers. The integrity log will show whether it does.

## Conditions before building far

1. **A license.** The repo has none, so contributions have no terms and nothing can legally be
   reused from it, or into it from Apache/MPL projects. `00-roadmap.md` Tier 0 already covers
   the options. This is the maintainer's call.
2. **The maintainer's agreement.** The repo is sidewinderzz's. Turning a "privacy-first blocker"
   into an accountability system is a change of product identity, not a feature. There are two
   paths:
   - **Same repo:** accountability is opt-in and additive, so the current blocker behaves
     identically for anyone who doesn't enable it. That's how this branch is built, with one
     exception: allowed-DNS logging defaults on, and that's flagged for review.
   - **Fork:** keep upstreaming filtering improvements back. A fork needs the license from
     condition 1.

## Verdict

Build on Focus Fence. Its strongest parts (the DNS path, the pure core and the scoped
accessibility pattern) are exactly what a detection-first product needs underneath. Nothing in
it blocks the accountability layers. The alternatives would mean rebuilding those parts first.

The first step was measurement, so that later decisions rest on data. That's what this branch
adds:
- an activity log, the raw detection record
- a diag log covering battery, resolver health, accessibility cost and error rates
- an integrity trail
- export, plus an off-device analyzer

[`03-measurement-plan.md`](03-measurement-plan.md) says what to run and what each result
decides.

## Sources

- Ever Accountable feature claims: its Play/App Store listing (reports websites, content inside
  apps, time in apps, optional screenshots, uninstall alerts, alert when filtering is disabled),
  the [Android user guide](https://en.everaccountable.com/blog/android-users-guide-to-ever-accountable-for-busy-people),
  the [Android help section](https://support.everaccountable.com/hc/en-us/sections/115000871251-Android),
  and third-party reviews ([learnofchrist.com](https://learnofchrist.com/resources/ever-accountable),
  [AirDroid comparison](https://www.airdroid.com/parental-insights/ever-accountable-vs-covenant-eyes/)).
- Déchaîner: [github.com/warleysr/dechainer](https://github.com/warleysr/dechainer).
- Play target-API requirement: [Play Console help](https://support.google.com/googleplay/android-developer/answer/11926878).
