package app.focusfence.dns

/**
 * Content categories (adult, gambling, …) from imported domain lists, applied
 * as **labels first**: every lookup that matches a category is recorded with
 * it, and only categories whose policy is [Policy.BLOCK] are blocked.
 * Classification ("what is this host?") is deliberately separate from policy
 * ("what do we do about it?") — a list saying a domain is adult never on its
 * own means "block forever".
 *
 * Precedence, highest first (see [decide]): a blocking user rule; the user
 * allowlist; a BLOCK-policy category; the ad list; the DoH guards. The
 * allowlist exists to override *imported* lists' false positives, so it never
 * overrides the user's own rules or the DoH guards (those protect the filter
 * itself). Pure JVM.
 */
object Categories {

    enum class Policy { OFF, REPORT, BLOCK;
        companion object {
            fun parse(s: String?): Policy = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: OFF
        }
    }

    /** One downloadable source. [url] is user-editable; nothing about a source is hardcoded at match time. */
    data class Source(val id: String, val label: String, val url: String, val license: String)

    /**
     * Built-in defaults only — the owner can point any category at another
     * list URL. Lists are downloaded at runtime, never bundled, so the app
     * doesn't redistribute them; check a list's license before bundling one.
     */
    val DEFAULT_SOURCES = listOf(
        Source("adult", "Adult", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/nsfw.txt", "GPL-3.0 (HaGeZi)"),
        Source("gambling", "Gambling", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt", "GPL-3.0 (HaGeZi)"),
        Source("social", "Social media", "", "user-supplied"),
    )

    /** Categories (sorted) whose list contains [qname] or a parent of it. */
    fun match(qname: String, lists: Map<String, Set<String>>): List<String> {
        if (lists.isEmpty()) return emptyList()
        var out: MutableList<String>? = null
        for ((cat, hosts) in lists) {
            if (Blocklist.matches(qname, hosts)) {
                if (out == null) out = ArrayList(2)
                out += cat
            }
        }
        return out?.sorted() ?: emptyList()
    }

    data class Decision(
        val blocks: Boolean,
        /** LogEvents.REASON_* of the deciding layer, or null for no match. */
        val reason: String?,
        val rule: BlockRule?,
        val categories: List<String>,
    )

    /**
     * The whole precedence in one pure function. [ruleBlocks] is the
     * (side-effecting, budget-accruing) user-rule check and is called at most once.
     */
    fun decide(
        qname: String,
        rule: BlockRule?,
        ruleBlocks: (BlockRule) -> Boolean,
        allowlist: Set<String>,
        categoryLists: Map<String, Set<String>>,
        policies: Map<String, Policy>,
        adHosts: Set<String>,
        guards: Set<String>,
    ): Decision {
        val cats = match(qname, categoryLists).filter { (policies[it] ?: Policy.OFF) != Policy.OFF }
        if (rule != null && ruleBlocks(rule)) return Decision(true, LogEvents.REASON_RULE, rule, cats)
        if (Blocklist.matches(qname, guards)) return Decision(true, LogEvents.REASON_GUARD, rule, cats)
        if (allowlist.isNotEmpty() && Blocklist.matches(qname, allowlist)) {
            return Decision(false, LogEvents.REASON_ALLOW, rule, cats)
        }
        if (cats.any { policies[it] == Policy.BLOCK }) return Decision(true, LogEvents.REASON_CATEGORY, rule, cats)
        if (adHosts.isNotEmpty() && qname in adHosts) return Decision(true, LogEvents.REASON_ADS, rule, cats)
        val reason = when {
            rule != null -> LogEvents.REASON_RULE
            cats.isNotEmpty() -> LogEvents.REASON_CATEGORY
            else -> null
        }
        return Decision(false, reason, rule, cats)
    }
}
