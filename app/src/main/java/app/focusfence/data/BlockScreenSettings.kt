package app.focusfence.data

import android.content.Context
import app.focusfence.dns.BlockScreen

/**
 * How a blocked site is announced (Settings → Filtering): a notification (the
 * default), the full "Blocked by Focus Fence" screen, or nothing. In the main
 * `focusfence` prefs file because both services act on it at runtime and the
 * prefs listener on that file is the only propagation mechanism.
 *
 * Changing it is not a loosening action: sites stay blocked either way, only
 * the explanation changes. So no strict-mode guard.
 */
object BlockScreenSettings {

    /**
     * Replaced the short-lived boolean `block_screen` (full screen on/off,
     * never in a release); that key is simply ignored now.
     */
    const val KEY_BLOCK_STYLE = "block_style"

    fun style(context: Context): BlockScreen.Style =
        BlockScreen.Style.parse(BlocklistStore.prefs(context).getString(KEY_BLOCK_STYLE, null))

    fun setStyle(context: Context, style: BlockScreen.Style) {
        BlocklistStore.prefs(context).edit().putString(KEY_BLOCK_STYLE, style.id).apply()
    }
}
