package app.focusfence

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        app.focusfence.sync.Sync.schedule(this)
        app.focusfence.update.Updates.schedule(this)
        watchForeground()
        // Managed mode was removed (D-028): undo anything an older version set on a device-owner phone.
        app.focusfence.admin.DeviceAdmin.clearLegacyPoliciesOnce(this)
    }

    /**
     * "Launching" the app, for the update check. The VPN service keeps this process alive, so a cold start is rare
     * and MainActivity usually survives a trip to another app: neither one means "the owner opened the app". What
     * does is the first of our screens becoming visible after none were, so count started activities. A rotation
     * stops and restarts a screen without leaving the app, so it doesn't count; neither does moving between our
     * own screens.
     */
    private fun watchForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private var rotating = false
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && !rotating) app.focusfence.update.Updates.launched()
                rotating = false
            }
            override fun onActivityStopped(a: Activity) {
                started--
                rotating = a.isChangingConfigurations
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
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
