package app.focusfence.data

import android.content.Context

/**
 * The "Show a blocked screen" switch (Settings → Filtering). In the main
 * `focusfence` prefs file because both services act on it at runtime and the
 * prefs listener on that file is the only propagation mechanism.
 *
 * Turning it off is not a loosening action: sites stay blocked either way, only
 * the explanation screen goes away. So no strict-mode guard.
 */
object BlockScreenSettings {

    const val KEY_BLOCK_SCREEN = "block_screen"

    /** On by default: a bare "site can't be reached" leaves people guessing what happened. */
    fun enabled(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_BLOCK_SCREEN, true)

    fun setEnabled(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_BLOCK_SCREEN, on).apply()
    }
}
