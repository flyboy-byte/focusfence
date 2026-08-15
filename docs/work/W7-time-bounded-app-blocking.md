# W7 — Time-bounded app blocking

**Track:** Product (B3a) · **Depends on:** W6 · **Blocks:** W8, W10 · **Status:** ✅ Done
**Size:** ~1 day including UI. First user-visible new capability in the plan.

## Why

App blocking is all-or-nothing and permanent. `BlockedAppsStore` is a bare `Set<String>` of package
names with **no time dimension at all**, while domains get four rule modes. So of the project's six
example boundaries, "make TikTok unavailable during a commitment period" and "block Instagram until
after work" are simply inexpressible.

This is the single biggest real gap in the app, and after W6 it is cheap: the accessibility service
already wakes on the right event for the right package and already holds a live snapshot. It just
has no way to ask "is this app blocked *right now*." W6 gives it one.

## Design

### Storage — backward compatible by construction

Reuse `BlockRule`'s encoding shape for apps, keyed by package name instead of domain:

| Meaning | Encoded |
|---|---|
| Always blocked | `com.zhiliaoapp.musically` |
| Daily window (may wrap midnight) | `com.zhiliaoapp.musically\|1320\|360` |
| Commitment until an instant | `com.zhiliaoapp.musically\|c\|1755180000000` |

**Existing installs need no migration.** Today's stored values are bare package names, which decode
as always-blocked under this scheme — identical behaviour. Do not write a migration; do not bump a
schema version.

`BlockRule.decode` already handles the 1-part and 3-part forms and the `b`/`p` tags. Add a `c` tag
for commitment. Because `BlockRule` has no field for an absolute instant, add one:

```kotlin
val commitUntilEpochMillis: Long = 0L
```

with `val hasCommitment: Boolean get() = commitUntilEpochMillis > 0L`, encoded as
`"$domain|c|$commitUntilEpochMillis"`, and decoded from the `c` tag. Keep `encode()`'s `when`
ordering deliberate: `hasPathExceptions` → `hasCommitment` → `hasBudget` → `isScheduled` → bare.

### Evaluation

Extend `RuleEvaluator.blocks` (from W6) to handle commitment:

```kotlin
if (rule.hasCommitment) return now.epochMillis < rule.commitUntilEpochMillis
```

Place it **after** the `hasPathExceptions` guard and **before** the budget branch.

Add an app-specific entry point, since apps have no hostname and no front-door concept:

```kotlin
/** True if an app-targeted rule blocks right now. Apps have no hostname, so the
 *  front-door and section-path logic does not apply. */
fun blocksApp(rule: BlockRule, now: Now): Boolean {
    if (rule.hasCommitment) return now.epochMillis < rule.commitUntilEpochMillis
    if (rule.hasBudget) return false   // app budgets need UsageStats — see W10
    return rule.activeAt(now.minuteOfDay)
}
```

`hasBudget` returning `false` is deliberate and must be commented: counting app foreground time
needs `UsageStatsManager`, which is W10. A budget on an app must not silently behave as
always-blocked.

### `BlockedAppsStore`

Change the read path to decode rules while keeping the same prefs key
(`KEY_BLOCKED_APPS = "blocked_apps"`) and the same `StringSet` type — that key is already handled
by both services' prefs listeners, and changing it would break propagation.

```kotlin
fun rulesByPackage(context: Context): Map<String, BlockRule>
fun setRules(context: Context, rules: Collection<BlockRule>)
```

Keep `get()`/`set()` as thin wrappers if other call sites need plain package sets — check
`SettingsFragment` and `UiUtil`'s app picker before removing anything.

### `UrlFilterService`

Replace the snapshot and the membership test.

```kotlin
@Volatile
private var appRules: Map<String, BlockRule> = emptyMap()
```

In `reloadRules()`, load `BlockedAppsStore.rulesByPackage(this)`.

In `applyEventFilter()`, `targets += appRules.keys` — **every** package with a rule, regardless of
whether it currently blocks. The service must keep receiving events for an app during its allowed
window, or it can't act when the window closes. Do not filter this set by current time.

In `onAccessibilityEvent`, replace:

```kotlin
if (eventPackage in blockedApps) {
```

with a lookup plus evaluation:

```kotlin
val appRule = appRules[eventPackage]
if (appRule != null && RuleEvaluator.blocksApp(appRule, nowFor(this))) {
```

Add a small private helper that builds `RuleEvaluator.Now` from `LocalTime.now()`,
`System.currentTimeMillis()`, `LocalDate.now()` and `PowerManager.isInteractive`. `UrlFilterService`
does not currently hold a `PowerManager`; obtain it in `onServiceConnected` and store it, rather
than calling `getSystemService` per event.

**Keep the existing debounce.** `APP_BLOCK_DEBOUNCE_MS = 2000L` and the `GLOBAL_ACTION_HOME` +
toast behaviour are unchanged.

### Timing behaviour, and its known limitation

Evaluation happens on `TYPE_WINDOW_STATE_CHANGED`, i.e. when an app is opened or switched to. That
means a window closing **while the user is already inside the app** does not eject them until the
next window event.

This is acceptable and should be documented rather than engineered around: the threat model is
impulsive circumvention, and the impulse being blocked is *opening the app*. Adding a timer to
evict a user mid-session would mean a periodic wakeup, which is exactly the kind of battery cost
this project refuses. **Do not add a scheduler, alarm or handler loop.**

### UI

`SettingsFragment` (or wherever the app picker currently lives — locate via `BlockedAppsStore`
call sites) needs a per-app mode choice mirroring the domain editor in `DomainDetailActivity`:
always / daily window / until a chosen time.

Reuse the existing time-picker patterns from `DomainDetailActivity` rather than inventing new ones,
and put all new strings in `res/values/strings.xml`.

### Strict mode

`StrictLock` is unchanged and remains global — it guards *edits to configuration*. A commitment is
a **condition**, not a second lock. What makes a commitment binding is that strict mode refuses to
shorten it. Check `StrictStore.isLocked` before allowing a commitment to be shortened or removed,
routing through `UiUtil.showStrictLockedDialog`, exactly as other destructive UI paths do. Adding
or lengthening a commitment is always allowed.

## Tests

Extend `RuleEvaluatorTest.kt` and add `BlockRuleTest.kt` cases:

- `BlockRule` round-trip: `encode()` → `decode()` for commitment, preserving the epoch value.
- `decode("com.foo")` yields an always-block rule — the backward-compatibility guarantee.
- `decode` rejects a malformed `c` payload (non-numeric, negative) by returning `null`.
- `blocksApp` with a commitment: true one millisecond before expiry, false at expiry, false after.
- `blocksApp` with a window: matches `activeAt` at boundaries, including a wrapping window.
- `blocksApp` with a budget returns **false** (documented W10 gap, not always-blocked).
- `blocksApp` with an always rule returns true.
- Encoding order: a rule with both a commitment and a budget encodes as the commitment form.

## Acceptance criteria

- [ ] A package name stored by the previous version still blocks, always — verified by a unit test
      on `decode`, not by inspection.
- [ ] `BlockedAppsStore` still writes to key `"blocked_apps"` in the `focusfence` prefs file, and
      both services' prefs listeners still fire on it.
- [ ] `UrlFilterService.applyEventFilter` registers every package with a rule, including ones not
      currently blocking.
- [ ] No timer, alarm, `Handler.postDelayed` loop or periodic wakeup is introduced —
      `grep -n "postDelayed\|AlarmManager\|scheduleAtFixedRate" app/src/main/java/app/focusfence/accessibility/UrlFilterService.kt`
      returns nothing.
- [ ] `grep -rn "^import android" app/src/main/java/app/focusfence/dns/` still returns nothing.
- [ ] `./gradlew testDebugUnitTest` passes with the new cases.
- [ ] A budget set on an app does not block the app.
- [ ] Shortening or removing a commitment while strict mode is locked is refused.
- [ ] All new user-facing strings are in `res/values/strings.xml`.

## Out of scope

- App **budgets** — needs `UsageStatsManager` (W10).
- The full boundary object spanning apps and domains together (W8). This work order deliberately
  keeps apps and domains in separate stores; unifying them is W8's job.
- Shizuku enforcement (W12). The mechanism stays the accessibility bounce.
- Evicting a user already inside an app when a window closes.
- Per-boundary strict locking beyond the check described above.
