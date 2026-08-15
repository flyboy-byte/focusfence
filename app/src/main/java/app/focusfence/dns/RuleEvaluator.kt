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
        if (rule.hasCommitment) return now.epochMillis < rule.commitUntilEpochMillis
        if (rule.hasBudget) {
            // Budgets track and enforce only the site's user-facing hostnames;
            // SDK/API subdomains (graph.facebook.com etc.) belong to other apps'
            // background traffic and must not drain or break anything.
            if (!Blocklist.isFrontDoor(qname, rule.domain)) return false
            return usedMillis >= rule.budgetMinutes * 60_000L
        }
        return rule.activeAt(now.minuteOfDay)
    }

    /**
     * True if an app-targeted [rule] blocks right now. Apps have no hostname, so the
     * front-door and section-path logic does not apply.
     */
    fun blocksApp(rule: BlockRule, now: Now): Boolean {
        if (rule.hasCommitment) return now.epochMillis < rule.commitUntilEpochMillis
        // Counting an app's foreground time needs UsageStatsManager (W10), which
        // does not exist yet. Returning false keeps an unsatisfiable budget
        // permissive; treating it as always-blocked would be a silent trap.
        if (rule.hasBudget) return false
        return rule.activeAt(now.minuteOfDay)
    }
}
