package app.focusfence.ui

import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as M
import com.google.android.material.color.MaterialColors

/**
 * State colours from the current theme, never fixed ones (D-032, revised 2026-11-24): "working" is the theme's
 * primary, "needs you" its tertiary, "broken" its error. With Material You those follow the wallpaper; without it
 * (Android 11 and older) the app's own green theme applies. The icon shape (check, alert, empty circle) carries the
 * meaning, so it reads the same in every palette.
 */
object StatusColors {
    private fun attr(v: View, a: Int) = MaterialColors.getColor(v, a)

    /** The "working" fill for a status card, and the text on it. */
    fun goodContainer(v: View) = attr(v, M.attr.colorPrimaryContainer)
    fun onGoodContainer(v: View) = attr(v, M.attr.colorOnPrimaryContainer)

    /** Secondary text on a "working" card: the card's text colour, a little quieter. */
    fun goodContainerMuted(v: View) = ColorUtils.setAlphaComponent(onGoodContainer(v), 0xC0)

    /** A check icon or headline that means "working". */
    fun good(v: View) = attr(v, androidx.appcompat.R.attr.colorPrimary)

    /** An alert icon or title that means "needs you". */
    fun warn(v: View) = attr(v, M.attr.colorTertiary)
}
