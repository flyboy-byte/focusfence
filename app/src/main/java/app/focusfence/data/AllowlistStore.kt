package app.focusfence.data

import app.focusfence.dns.Blocklist
import android.content.Context

/** Domains exempted from list-sourced blocks (ads, DoH guards) — never from a user
 *  rule the user deliberately set. See DECISIONS.md D-017. */
object AllowlistStore {

    const val KEY_ALLOWLIST = "allowlist"

    fun get(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()

    fun add(context: Context, domain: String) {
        val normalized = Blocklist.normalize(domain) ?: return
        set(context, get(context) + normalized)
    }

    fun remove(context: Context, domain: String) {
        set(context, get(context) - domain)
    }

    private fun set(context: Context, domains: Set<String>) {
        BlocklistStore.prefs(context).edit().putStringSet(KEY_ALLOWLIST, domains).apply()
    }
}
