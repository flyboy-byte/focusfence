package app.focusfence.ui

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Rounded list groups (D-032, 2026-11-25), like Android's own Settings: each row is a tile on the surface-container
 * colour, a group's ends are rounded 24dp and the joins 6dp, with a 2dp gap between rows. Colours come from the
 * theme, so they follow the wallpaper. Applied in code because the shape depends on where a row sits among the
 * rows that are actually showing.
 */
object Tiles {
    enum class Pos { SINGLE, TOP, MIDDLE, BOTTOM }

    fun pos(index: Int, count: Int): Pos = when {
        count <= 1 -> Pos.SINGLE
        index == 0 -> Pos.TOP
        index == count - 1 -> Pos.BOTTOM
        else -> Pos.MIDDLE
    }

    fun style(v: View, pos: Pos, clickable: Boolean = v.hasOnClickListeners()) {
        val d = v.resources.displayMetrics.density
        val big = 24 * d
        val small = 6 * d
        val top = if (pos == Pos.SINGLE || pos == Pos.TOP) big else small
        val bottom = if (pos == Pos.SINGLE || pos == Pos.BOTTOM) big else small
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top).setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
            .build()
        val fill = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(v, com.google.android.material.R.attr.colorSurfaceContainer))
        }
        v.background = if (clickable) {
            val ripple = MaterialColors.getColor(v, android.R.attr.colorControlHighlight)
            RippleDrawable(ColorStateList.valueOf(ripple), fill, MaterialShapeDrawable(shape))
        } else fill
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = if (pos == Pos.SINGLE || pos == Pos.TOP) 0 else (2 * d).toInt()
            v.layoutParams = it
        }
    }

    /** Styles the visible children of [group] as one rounded group. Call again after showing or hiding a row. */
    fun group(group: ViewGroup) {
        val shown = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        shown.forEachIndexed { i, v -> style(v, pos(i, shown.size)) }
    }
}
