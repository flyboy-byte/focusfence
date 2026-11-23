package app.focusfence.data

import android.content.Context
import app.focusfence.dns.Blocklist

/**
 * User allowlist: domains that override *imported-list* blocks (category lists
 * and the ad list) for false positives. By design it does **not** override the
 * user's own block rules or the DoH guards — see [app.focusfence.dns.Categories.decide].
 * Stored as a normalized `StringSet` in the `focusfence` prefs file so the VPN
 * service's existing change-listener picks it up.
 */
object AllowlistStore {

    const val KEY_ALLOWLIST = "allowlist"

    fun get(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_ALLOWLIST, emptySet())?.toSet() ?: emptySet()

    fun add(context: Context, domain: String) {
        val norm = Blocklist.normalize(domain) ?: return
        BlocklistStore.prefs(context).edit()
            .putStringSet(KEY_ALLOWLIST, get(context) + norm)
            .apply()
    }

    fun remove(context: Context, domain: String) {
        BlocklistStore.prefs(context).edit()
            .putStringSet(KEY_ALLOWLIST, get(context) - domain)
            .apply()
    }
}
