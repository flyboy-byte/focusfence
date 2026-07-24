package app.focusfence.data

import android.content.Context
import android.content.SharedPreferences
import app.focusfence.dns.BlockRule

/** Persists the user's block rules in SharedPreferences. */
object BlocklistStore {

    const val PREFS_NAME = "focusfence"
    const val KEY_RULES = "block_rules"

    val SOCIAL_MEDIA_PRESETS: List<String> = listOf(
        "facebook.com",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com",
        "reddit.com",
        "snapchat.com",
        "pinterest.com",
        "tumblr.com",
        "threads.net",
    )

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rules(context: Context): List<BlockRule> =
        (prefs(context).getStringSet(KEY_RULES, emptySet()) ?: emptySet())
            .mapNotNull { BlockRule.decode(it) }
            .sortedBy { it.domain }

    fun rulesByDomain(context: Context): Map<String, BlockRule> =
        rules(context).associateBy { it.domain }

    fun addAll(context: Context, domains: Collection<String>) {
        val existing = rulesByDomain(context)
        val newRules = domains.filter { it !in existing }.map { BlockRule(it) }
        if (newRules.isNotEmpty()) save(context, rules(context) + newRules)
    }

    /** Adds or replaces the rule for [rule]'s domain. */
    fun upsert(context: Context, rule: BlockRule) {
        // Saving a budget rule starts the day's count fresh — also the escape
        // hatch when tracking has miscounted. Written before the rules so the
        // service's reload sees the cleared value.
        if (rule.hasBudget) {
            UsageStore.save(context, rule.domain, java.time.LocalDate.now().toString(), 0L)
        }
        save(context, rules(context).filterNot { it.domain == rule.domain } + rule)
    }

    fun remove(context: Context, domain: String) {
        save(context, rules(context).filterNot { it.domain == domain })
    }

    private fun save(context: Context, rules: List<BlockRule>) {
        prefs(context).edit()
            .putStringSet(KEY_RULES, rules.map { it.encode() }.toSet())
            .apply()
    }
}
