package app.focusfence

import android.app.Application
import app.focusfence.data.FilterListStore
import com.google.android.material.color.DynamicColors

class FocusFenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: derive the palette from the user's wallpaper on Android 12+;
        // older devices keep the static Material 3 theme.
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Runs before any service or UI in this process touches the store, so
        // both only ever see the per-source scheme.
        FilterListStore.ensureMigrated(this)
    }
}
