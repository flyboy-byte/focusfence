package app.focusfence.data

import android.content.Context

/** Apps excluded from the VPN entirely — their traffic bypasses the filter,
 *  so nothing they do is counted against budgets or blocked. */
object ExcludedAppsStore {

    const val KEY_EXCLUDED_APPS = "excluded_apps"

    fun get(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()

    fun set(context: Context, packages: Set<String>) {
        BlocklistStore.prefs(context).edit().putStringSet(KEY_EXCLUDED_APPS, packages).apply()
    }
}
