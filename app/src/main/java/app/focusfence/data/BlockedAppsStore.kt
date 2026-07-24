package app.focusfence.data

import android.content.Context

/** Persists the set of blocked app package names. Lives in the main prefs file
 *  so the accessibility service's existing listener picks up changes. */
object BlockedAppsStore {

    const val KEY_BLOCKED_APPS = "blocked_apps"

    fun get(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

    fun set(context: Context, packages: Set<String>) {
        BlocklistStore.prefs(context).edit().putStringSet(KEY_BLOCKED_APPS, packages).apply()
    }
}
