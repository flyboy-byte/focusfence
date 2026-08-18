package app.focusfence

import android.app.Application
import app.focusfence.data.FilterListStore

class FocusFenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs before any service or UI in this process touches the store, so
        // both only ever see the per-source scheme.
        FilterListStore.ensureMigrated(this)
    }
}
