package app.focusfence.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.monitor.Monitor

/**
 * Uninstall-protection (device admin) receiver. The project's stance is **detect and
 * notify, not block**: we do not call setUninstallBlocked, so removal stays
 * possible. What this does instead is make any attempt to remove protection
 * loud — every admin lifecycle event is written to the activity log as an
 * urgent integrity record, so a partner (once sync exists) sees it, and the
 * local record shows it even offline.
 *
 * onDisableRequested returns a warning string shown before the admin is
 * removed: friction, not a wall.
 */
class FocusFenceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        log(context, "admin_enabled")
        Monitor.checkIntegrityAsync(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // The removal is still allowed; we record the attempt and warn.
        log(context, "admin_disable_requested")
        // "Deactivate & uninstall" can remove the app seconds from now, before any scheduled sync. Send the alert
        // straight away (goAsync keeps the process up for this; offline, the "phone went silent" alert still follows).
        val pending = goAsync()
        Thread {
            try { app.focusfence.sync.SyncWorker.pushAlertsNow(context.applicationContext) } finally { pending.finish() }
        }.start()
        return context.getString(app.focusfence.R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        log(context, "admin_disabled")
        Monitor.checkIntegrityAsync(context)
    }

    private fun log(context: Context, state: String) {
        EventLog.init(context)
        EventLog.activity(
            LogEvents.service(System.currentTimeMillis(), "admin", state),
            urgent = true,
        )
        EventLog.flushAsync()
    }
}
