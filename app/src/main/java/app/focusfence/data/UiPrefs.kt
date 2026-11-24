package app.focusfence.data

import android.content.Context

/**
 * Layout choice (2026-11-23 redesign). The five-tab layout is the default; "Classic layout" in Settings brings back
 * the old four tabs with every screen still opening on its own, so the redesign can be rolled back on the phone
 * without a release. Own prefs file: nothing at runtime depends on it, so it stays out of the `focusfence` listeners.
 */
object UiPrefs {
    private const val FILE = "focusfence_ui"
    private const val KEY_CLASSIC = "classic_layout"

    fun classic(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_CLASSIC, false)

    fun setClassic(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLASSIC, on).apply()
    }
}
