# W6 — Extract rule evaluation into `dns/`

**Track:** Product (B1) · **Depends on:** nothing · **Blocks:** W7, W8, W9 · **Status:** ✅ Done
**Size:** ~half a day. Pure refactor — **zero behaviour change**, and that's the acceptance bar.

## Why

`DnsVpnService.ruleBlocks()` decides whether a rule is in force right now. It is the only decision
logic in the app that lives outside the pure `dns/` package, which means:

- It can't be unit-tested. Everything it does — window evaluation, budget accrual, the front-door
  check — is only reachable by running the VPN service.
- **The accessibility service can't reach it at all.** That is the actual blocker: app blocking is
  currently `if (eventPackage in blockedApps)` with no notion of *when*, because the code that
  knows about time lives in a different service. W7 makes app blocking time-aware, and it is a
  small change *only if* this extraction happens first.

This also implements the recorded principle that observation, classification, policy and
enforcement are separate concerns (D-011). Right now `ruleBlocks()` does all four.

## Current code

`app/src/main/java/app/focusfence/vpn/DnsVpnService.kt`:

```kotlin
private fun ruleBlocks(rule: BlockRule, qname: String): Boolean {
    // Section rules must stay resolvable so the allowed paths work; the
    // accessibility URL filter enforces them in the browser instead.
    if (rule.hasPathExceptions) return false
    if (rule.hasBudget) {
        // Budgets track and enforce only the site's user-facing hostnames;
        // SDK/API subdomains (graph.facebook.com etc.) belong to other
        // apps' background traffic and must not drain or break anything.
        if (!Blocklist.isFrontDoor(qname, rule.domain)) return false
        val dayKey = LocalDate.now().toString()
        val used = usageTracker.recordAndGetUsage(
            rule.domain,
            System.currentTimeMillis(),
            dayKey,
            accrue = powerManager.isInteractive,
        )
        persistUsageThrottled(rule.domain, dayKey, used)
        return used >= rule.budgetMinutes * 60_000L
    }
    val now = LocalTime.now()
    return rule.activeAt(now.hour * 60 + now.minute)
}
```

Called from exactly one place:

```kotlin
private fun blockReason(qname: String): String? {
    val rule = Blocklist.findRule(qname, rules)
    if (rule != null && ruleBlocks(rule, qname)) return rule.domain
    ...
}
```

Note the method **mixes a pure decision with two side effects** (usage accrual and a throttled
prefs write). The extraction has to separate those, not hide them.

## Design

Create `app/src/main/java/app/focusfence/dns/RuleEvaluator.kt`. **No Android imports** — that's the
whole point, and it's enforced by acceptance criteria below.

```kotlin
package app.focusfence.dns

/**
 * Decides whether a [BlockRule] is in force at a given moment. Pure: the caller
 * supplies the clock, the screen state and any accrued usage, so this is JVM-testable
 * and reachable from both the VPN service and the accessibility service.
 */
object RuleEvaluator {

    /** Everything time- and device-dependent that a decision needs. */
    data class Now(
        val minuteOfDay: Int,   // 0..1439
        val epochMillis: Long,
        val dayKey: String,     // "yyyy-mm-dd"
        val screenOn: Boolean,
    )

    /**
     * True if this rule needs a usage figure before [blocks] can answer — i.e. it is a
     * budget rule and [qname] is one of the domain's front doors. Callers that get
     * `true` must accrue usage and pass the result to [blocks]; callers that get
     * `false` pass 0.
     */
    fun requiresUsage(rule: BlockRule, qname: String): Boolean =
        !rule.hasPathExceptions &&
            rule.hasBudget &&
            Blocklist.isFrontDoor(qname, rule.domain)

    /**
     * True if [rule] blocks right now. [usedMillis] is today's accrued usage for the
     * rule's domain, and is only consulted for budget rules.
     */
    fun blocks(rule: BlockRule, qname: String, now: Now, usedMillis: Long = 0L): Boolean {
        // Section rules must stay resolvable so the allowed paths work; the
        // accessibility URL filter enforces them in the browser instead.
        if (rule.hasPathExceptions) return false
        if (rule.hasBudget) {
            // Budgets track and enforce only the site's user-facing hostnames;
            // SDK/API subdomains (graph.facebook.com etc.) belong to other apps'
            // background traffic and must not drain or break anything.
            if (!Blocklist.isFrontDoor(qname, rule.domain)) return false
            return usedMillis >= rule.budgetMinutes * 60_000L
        }
        return rule.activeAt(now.minuteOfDay)
    }
}
```

`DnsVpnService.ruleBlocks()` becomes the orchestration — observation and side effects stay in the
service, the decision moves out:

```kotlin
private fun ruleBlocks(rule: BlockRule, qname: String): Boolean {
    val localTime = LocalTime.now()
    val now = RuleEvaluator.Now(
        minuteOfDay = localTime.hour * 60 + localTime.minute,
        epochMillis = System.currentTimeMillis(),
        dayKey = LocalDate.now().toString(),
        screenOn = powerManager.isInteractive,
    )
    var used = 0L
    if (RuleEvaluator.requiresUsage(rule, qname)) {
        used = usageTracker.recordAndGetUsage(
            rule.domain, now.epochMillis, now.dayKey, accrue = now.screenOn,
        )
        persistUsageThrottled(rule.domain, now.dayKey, used)
    }
    return RuleEvaluator.blocks(rule, qname, now, used)
}
```

### Behaviour-equivalence notes — read before implementing

- `requiresUsage` returns `false` for `hasPathExceptions` rules, so a section rule never accrues
  usage. That matches today: the old code returns at the `hasPathExceptions` guard before reaching
  the budget branch.
- `requiresUsage` folds in the `isFrontDoor` check, so a non-front-door lookup on a budget rule
  accrues nothing — same as today, where `isFrontDoor` short-circuits before `recordAndGetUsage`.
- `blocks` re-checks `isFrontDoor` for budget rules. That's intentional redundancy so the function
  is correct when called directly (W7 will), not just when preceded by `requiresUsage`.
- `Now.epochMillis` and `Now.dayKey` are derived from separate calls in the old code
  (`System.currentTimeMillis()` and `LocalDate.now()`). Capturing both once per evaluation is
  marginally *more* correct and is not a behaviour change worth worrying about.
- `screenOn` is unused by `blocks` today. It's in `Now` because W7 and W10 need it and because the
  accrual decision belongs to the same "what is true right now" bundle. **Do not** add usage of it
  in this work order.

## Tests

New file `app/src/test/java/app/focusfence/dns/RuleEvaluatorTest.kt`. Follow the style of the
existing tests in that directory (plain JUnit 4, no mocking framework).

Required cases:

- **Always rule** — `blocks` true at any `minuteOfDay`.
- **Window rule, non-wrapping** (`540..1080`) — false at 539, true at 540, true at 1079, false at
  1080. Exercises the existing boundary semantics; `activeAt` uses `until`, so the end minute is
  exclusive.
- **Window rule, wrapping midnight** (`1320..360`) — true at 1320, true at 0, true at 359, false at
  360, false at 700.
- **Budget rule, front door, under budget** — `requiresUsage` true, `blocks` false.
- **Budget rule, front door, at exactly budget** — `blocks` true (the comparison is `>=`).
- **Budget rule, non-front-door subdomain** (`graph.facebook.com` against a `facebook.com` rule) —
  `requiresUsage` false **and** `blocks` false even when `usedMillis` is far over budget. This is
  the regression that broke third-party apps; it must have a test.
- **Section rule** — `requiresUsage` false, `blocks` false regardless of time or usage.

## Acceptance criteria

- [ ] `app/src/main/java/app/focusfence/dns/RuleEvaluator.kt` exists and
      `grep -c "^import android" app/src/main/java/app/focusfence/dns/RuleEvaluator.kt` returns 0.
- [ ] `grep -rn "^import android" app/src/main/java/app/focusfence/dns/` returns nothing (the
      package-wide purity invariant still holds).
- [ ] `RuleEvaluatorTest.kt` exists and covers all eight cases above.
- [ ] `./gradlew testDebugUnitTest` passes, and the run includes `RuleEvaluatorTest`.
- [ ] `DnsVpnService.ruleBlocks()` contains **no** window arithmetic, no `budgetMinutes`
      comparison, and no `isFrontDoor` call — it only builds `Now`, accrues, and delegates.
- [ ] `git diff` touches exactly: `RuleEvaluator.kt` (new), `RuleEvaluatorTest.kt` (new),
      `DnsVpnService.kt`. Nothing else.
- [ ] **Behaviour equivalence stated explicitly in the PR body:** confirm no user-visible change,
      and note that `blockReason` still returns `rule.domain` for the same inputs as before.

## Out of scope

- Any behaviour change at all, including "obvious" improvements to budget or window semantics.
- Making the accessibility service use this (that's W7).
- Moving `UsageTracker` calls or the throttled prefs write into `dns/` — they are side effects and
  belong in the service.
- Touching `blockReason`'s ad-host or DoH-guard branches.
- Renaming `ruleBlocks`.
