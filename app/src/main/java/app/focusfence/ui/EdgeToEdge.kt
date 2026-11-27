package app.focusfence.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.focusfence.R

/**
 * Android 15 draws every screen of an app that targets SDK 35 under the status and navigation bars (edge-to-edge),
 * and no longer resizes the window for the keyboard. None of our 21 screens were laid out for that, so instead of
 * changing each one this pads every screen's content by the bars, the camera cutout and the keyboard, which is how
 * they looked before. The theme's bar colours are ignored on 15, so the bars show the window background (the same
 * surface colour). Consuming the insets here means a layout's own `fitsSystemWindows` doesn't pad a second time.
 *
 * Called once per screen from [app.focusfence.FocusFenceApp] at onStart: by then setContentView has run (AppCompat
 * moves `android.R.id.content` to its own frame there) and the first layout, which delivers insets, hasn't.
 */
object EdgeToEdge {
    fun pad(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        if (content.getTag(R.id.edge_to_edge_padded) != null) return
        content.setTag(R.id.edge_to_edge_padded, true)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }
}
