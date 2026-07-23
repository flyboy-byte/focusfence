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

    fun add(context: Context, domain: String) {
        if (rulesByDomain(context).containsKey(domain)) return
        save(context, rules(context) + BlockRule(domain))
    }

    fun addAll(context: Context, domains: Collection<String>) {
        val existing = rulesByDomain(context)
        val newRules = domains.filter { it !in existing }.map { BlockRule(it) }
        if (newRules.isNotEmpty()) save(context, rules(context) + newRules)
    }

    fun setSchedule(context: Context, domain: String, startMinute: Int, endMinute: Int) {
        save(context, rules(context).map {
            if (it.domain == domain) BlockRule(domain, startMinute, endMinute) else it
        })
    }

    fun clearSchedule(context: Context, domain: String) {
        save(context, rules(context).map {
            if (it.domain == domain) BlockRule(domain) else it
        })
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
