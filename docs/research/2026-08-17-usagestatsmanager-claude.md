# Q4 — `UsageStatsManager` behaviour and grant flow (for W10 app budgets)

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items")
**Date:** 2026-08-17
**Feeds:** W10 (`docs/archive/work/W10-app-budgets-usagestats.md`)
**Sourcing method:** the official Android reference pages, fetched 2026-08-17:
`UsageStatsManager`, `UsageStats`, `UsageEvents.Event`, `Settings`, `AppOpsManager`
(all under https://developer.android.com/reference/). Claims not backed by those pages are
explicitly flagged as engineering experience rather than sourced fact.

---

## Exact prompt used

The Q4 prompt from `docs/research/prompts.md` (four questions: what "foreground time" actually
is, the grant flow, accuracy/granularity limitations, and per-event query cost), reproduced
there verbatim — not repeated here since that file is the canonical copy.

---

## Headline answer for W10's design

**Use `queryEvents`, not `queryUsageStats`, for the live same-day budget.** Sum
`ACTIVITY_RESUMED → ACTIVITY_PAUSED` intervals per package since local midnight. That gives
attention-time semantics (PiP/background playback do *not* accrue), fine granularity, and no
dependence on how the system slices daily buckets. The API-26..28 device path must read the
deprecated `MOVE_TO_FOREGROUND`/`MOVE_TO_BACKGROUND` constants instead — same loop, different
event-type constants.

## Findings, question by question

### 1. What "foreground time" actually means

- `UsageEvents.Event.ACTIVITY_RESUMED` — "An event type denoting that an Activity moved to the
  foreground"; `ACTIVITY_PAUSED` — "moved to the background"; `ACTIVITY_STOPPED` — "an activity
  becomes invisible on the UI, corresponding to `Activity.onStop()`". `MOVE_TO_FOREGROUND` /
  `MOVE_TO_BACKGROUND` are "deprecated in API level 29, by ACTIVITY_RESUMED / ACTIVITY_PAUSED"
  (UsageEvents.Event reference). With `minSdk 26`, both families must be handled: pre-29 devices
  emit only the old constants.
- `UsageStats` (the aggregate object) exposes three distinct clocks, and the distinction is
  exactly W10's PiP question: `getTotalTimeInForeground()` ("total time this package spent in
  the foreground"), `getTotalTimeVisible()` ("total time this package's activity is visible in
  the UI"), and `getTotalTimeForegroundServiceUsed()` (foreground services). A
  picture-in-picture or otherwise visible-but-not-focused activity is *visible* without being
  *resumed*, so resumed-interval time is the "user is actually using it" number a self-control
  budget wants. (The mapping of PiP to the paused-but-visible state is standard Activity
  lifecycle behaviour; flagged as doc-adjacent reasoning rather than a quoted sentence.)
- Consequence: **background music/PiP video does not drain an app budget** under the
  events-based approach — the analogue of the DNS layer's front-door rule, and the answer the
  W10 sketch needs.

### 2. Grant flow

- Confirmed from the `UsageStatsManager` page: "Most methods on this API require the permission
  `android.permission.PACKAGE_USAGE_STATS`. However, declaring the permission implies intention
  to use the API and the user of the device still needs to grant permission through the
  Settings application. See `Settings.ACTION_USAGE_ACCESS_SETTINGS`." So: manifest declaration
  + a Settings deep-link (`ACTION_USAGE_ACCESS_SETTINGS`, "Show settings to control access to
  usage information") — **no runtime dialog exists**. The user lands on a list of apps, taps
  Focus Fence, toggles "Permit usage access". W10's UI must explain this before launching the
  intent, because the flow is unguided once in Settings.
- Checking whether the grant happened: `AppOpsManager.OPSTR_GET_USAGE_STATS` ("Access to
  UsageStatsManager") via `unsafeCheckOpNoThrow(op, uid, packageName)`. Note for the future:
  that method is marked deprecated **in API level 36** in favor of `checkOpNoThrow` — with
  `compileSdk 34` the current name is fine today, but W10 should isolate the check in one
  helper so the eventual rename is one line.

### 3. Granularity, lag, OEM behaviour

- Doc-sourced: "Usage data is aggregated into time intervals: days, weeks, months, and years"
  and "A request for data in the middle of a time interval will include that interval" — i.e.
  `queryUsageStats(INTERVAL_DAILY, …)` returns a *partial, still-accumulating* bucket for
  today, aggregated on the system's schedule, and the API contract nowhere promises how fresh
  that aggregate is. `INTERVAL_BEST` picks a bucket size for you, which is worse for a live
  budget, not better.
- The events path (`queryEvents(beginTime, endTime)`, "Query for events in the given time
  range") sidesteps aggregation entirely: the caller computes today's total from raw
  resume/pause timestamps, so freshness is bounded by event delivery, not bucket maintenance.
  This is why the headline recommendation is events-based.
- **Flagged as engineering experience, not doc-sourced:** same-day `queryUsageStats` rows are
  widely reported to split/duplicate across bucket boundaries (a package can appear in several
  `UsageStats` rows for one day) and to update lazily; OEM battery managers (Samsung/Xiaomi)
  are known to interfere with *background services*, but `UsageStatsManager` reads are a
  pull-model system query and are not specifically known to be OEM-broken. Neither claim has
  an official citation because Google documents neither; both are testable in W10's on-device
  pass. Event *retention* is similarly undocumented on the current reference page — historic
  docs said events are kept only for a few days, which is irrelevant for a same-day budget but
  means **do not** design any multi-week history feature on `queryEvents`.

### 4. Cost of querying per accessibility event

- No official cost numbers exist. Structural answer: `queryEvents` is a Binder call into system
  server that materializes every usage event in the range — calling it on every
  `TYPE_WINDOW_STATE_CHANGED` with a from-midnight range is O(events-today) work per app
  switch, growing through the day. That is the wrong shape regardless of the constant factor.
  **Design consequence (matches the W10 sketch's caching instinct):** keep a per-package
  accumulated total in memory, and on each budget check query only the *delta* window
  (`lastQueryTime → now`), folding new intervals into the total; re-anchor at midnight. That
  bounds each check to the events since the previous check — typically zero or two events —
  and the accessibility handler stays cheap the way the battery constraints require.

## Design consequences for W10 (to fold into the work order)

1. Events-based accumulator (`ACTIVITY_RESUMED`/`PAUSED`, plus the deprecated pair on API
   26–28), delta-windowed, re-anchored at local midnight. No `queryUsageStats` anywhere in the
   budget path.
2. The pure side stays pure: `RuleEvaluator.blocksApp` gains a `usedMillis` parameter exactly
   like `blocks`; the accumulator lives Android-side and supplies the number.
3. An open interval (app currently resumed) counts as `now - resumeTime` when evaluating —
   otherwise a user inside the app never hits the budget until they leave it.
4. Grant helper: one function wrapping the AppOps check + the Settings deep-link; budgets UI
   shows "unavailable, tap to grant" when the check fails (W10 acceptance criterion already).
5. On-device verification list for W10: PiP does not accrue; split-screen (multi-resume,
   Android 10+) may accrue for *both* visible apps — decide whether that is acceptable
   (probably yes: both are genuinely on screen); pre-29 device or emulator run for the
   deprecated-constants path.

## What would change this

Nothing here blocks W10 — the design question the work order flagged ("if the API's actual
granularity or lag makes same-day budgets unreliable, the design needs to change before code")
resolves as: aggregation lag is real but avoidable entirely by not using the aggregates.
