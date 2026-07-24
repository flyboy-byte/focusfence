package app.focusfence

import android.app.Application
import com.google.android.material.color.DynamicColors

class FocusFenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: derive the palette from the user's wallpaper on Android 12+;
        // older devices keep the static Material 3 theme.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
