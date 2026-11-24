package app.focusfence.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * The fixed "working" green and "needs you" amber, harmonized with the current Material You palette: shifted a
 * little toward the wallpaper's primary hue (Material's own method), so they still read as green and amber but
 * never clash with whatever colours Android picked. Use these instead of the raw color resources.
 */
object StatusColors {
    fun get(context: Context, colorRes: Int): Int =
        MaterialColors.harmonizeWithPrimary(context, ContextCompat.getColor(context, colorRes))
}
