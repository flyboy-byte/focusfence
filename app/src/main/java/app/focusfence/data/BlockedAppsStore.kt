package app.focusfence.data

import android.content.Context
import app.focusfence.dns.BlockRule

/** Persists blocked-app rules, keyed by package name. Lives in the main prefs file
 *  so the accessibility service's existing listener picks up changes.
 *
 *  Values reuse [BlockRule]'s encoding with the package name in the `domain`
 *  slot. A bare package name — everything written by versions before app rules
 *  gained a time dimension — decodes as an always-block rule, so existing
 *  installs carry over untouched and no migration is needed. */
object BlockedAppsStore {

    const val KEY_BLOCKED_APPS = "blocked_apps"

    fun rules(context: Context): List<BlockRule> =
        (BlocklistStore.prefs(context).getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet())
            .mapNotNull { BlockRule.decode(it) }
            .sortedBy { it.domain }

    fun rulesByPackage(context: Context): Map<String, BlockRule> =
        rules(context).associateBy { it.domain }

    fun setRules(context: Context, rules: Collection<BlockRule>) {
        BlocklistStore.prefs(context).edit()
            .putStringSet(KEY_BLOCKED_APPS, rules.map { it.encode() }.toSet())
            .apply()
    }

    /** Adds or replaces the rule for [rule]'s package. */
    fun upsert(context: Context, rule: BlockRule) {
        setRules(context, rules(context).filterNot { it.domain == rule.domain } + rule)
    }

    /** The set of packages that have any rule at all, blocking now or not. */
    fun get(context: Context): Set<String> = rulesByPackage(context).keys

    /** Replaces the whole set, preserving the existing rule for any package that
     *  is kept — otherwise the app picker would silently flatten a window or a
     *  commitment back to always-blocked. */
    fun set(context: Context, packages: Set<String>) {
        val existing = rulesByPackage(context)
        setRules(context, packages.map { existing[it] ?: BlockRule(it) })
    }
}
