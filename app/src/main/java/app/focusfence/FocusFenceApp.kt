package app.focusfence

import android.app.Application
import android.provider.Settings
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.monitor.Monitor
import com.google.android.material.color.DynamicColors

class FocusFenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: derive the palette from the user's wallpaper on Android 12+;
        // older devices keep the static Material 3 theme.
        DynamicColors.applyToActivitiesIfAvailable(this)

        EventLog.init(this)
        logStartup()
        Monitor.checkIntegrityAsync(this)
    }

    /**
     * Every process start is logged, plus reboots and app version changes seen
     * since the last start. A process start means anything buffered in memory
     * by the previous process may be gone; recording it keeps that visible.
     */
    private fun logStartup() {
        val now = System.currentTimeMillis()
        val prefs = EventLog.prefs()
        val bootCount = try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: SecurityException) {
            -1
        }
        val version = BuildConfigCompat.versionName(this)
        val lastBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        val lastVersion = prefs.getString(KEY_VERSION, null)

        EventLog.activity(
            LogEvents.service(now, "app", "process_start", "boot=$bootCount,version=$version"),
            urgent = true,
        )
        if (bootCount >= 0 && lastBoot >= 0 && bootCount != lastBoot) {
            EventLog.activity(LogEvents.service(now, "device", "rebooted", "boot=$bootCount"), urgent = true)
        }
        if (lastVersion != null && lastVersion != version) {
            EventLog.activity(
                LogEvents.service(now, "app", "updated", "from=$lastVersion,to=$version"),
                urgent = true,
            )
        }
        prefs.edit().putInt(KEY_BOOT_COUNT, bootCount).putString(KEY_VERSION, version).apply()
    }

    private companion object {
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_VERSION = "app_version"
    }
}

/** Version name without depending on the generated BuildConfig (disabled by default in AGP 8). */
object BuildConfigCompat {
    fun versionName(app: Application): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
