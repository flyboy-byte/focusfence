package app.focusfence.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents

/**
 * Uninstall protection: device admin with no policies (Ever Accountable's model). While active, Android won't
 * uninstall the app until admin is turned off first; [FocusFenceAdminReceiver] logs every step.
 *
 * Device owner ("managed mode": Private DNS strategy, always-on VPN, settings lock, app suspension, network logging)
 * was removed on 2026-11-24. [relinquish] stays so a phone provisioned by an older version can leave it cleanly,
 * undoing every policy those versions could have set.
 */
object DeviceAdmin {

    private const val TAG = "FocusFenceAdmin"

    fun component(context: Context) = ComponentName(context, FocusFenceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isAdminActive(context: Context): Boolean =
        dpm(context)?.isAdminActive(component(context)) == true

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true

    /** Turns uninstall protection off (an app may remove its own admin). The receiver logs `admin_disabled`. */
    fun deactivate(context: Context) {
        runCatching { dpm(context)?.removeActiveAdmin(component(context)) }
            .onFailure { Log.w(TAG, "removeActiveAdmin failed", it) }
    }

    /** Leaves device ownership set up by an older version, clearing everything it may have set. Never bricks. */
    fun relinquish(context: Context): Boolean {
        val dpm = dpm(context) ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = component(context)
        return try {
            runCatching { dpm.setNetworkLoggingEnabled(admin, false) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
            runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) }
            }
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), "admin", "relinquish", "device_owner"), urgent = true)
            dpm.clearDeviceOwnerApp(context.packageName)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "relinquish refused", e)
            false
        }
    }
}
