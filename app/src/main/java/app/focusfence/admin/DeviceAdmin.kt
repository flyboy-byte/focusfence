package app.focusfence.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents

/**
 * Thin, fail-safe wrapper over [DevicePolicyManager] for the optional managed
 * ("teeth") mode. Every call no-ops and returns false when the app is not the
 * device owner or the OS refuses, so nothing here can throw into normal flows.
 *
 * Powers used (device-owner only), all in service of detect-and-notify plus the
 * strategy switch — never uninstall blocking:
 *  - Private DNS lockdown (frees the VPN slot while filtering continues),
 *  - network logging (package-attributed DNS, fills the "which app" gap),
 *  - package suspension (OS-level app blocking, stronger than the a11y bounce).
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

    /** Points global Private DNS at [host] (DoT). Device-owner only. */
    fun setPrivateDns(context: Context, host: String): Boolean = guarded(context) { dpm, admin ->
        val code = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
        val ok = code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        audit(context, "private_dns_set", if (ok) host else "error:$code")
        ok
    } ?: false

    /** Returns Private DNS to automatic (opportunistic), undoing our host. */
    fun clearPrivateDns(context: Context): Boolean = guarded(context) { dpm, admin ->
        val code = dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        val ok = code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        audit(context, "private_dns_clear", if (ok) "opportunistic" else "error:$code")
        ok
    } ?: false

    fun setNetworkLogging(context: Context, enabled: Boolean): Boolean = guarded(context) { dpm, admin ->
        dpm.setNetworkLoggingEnabled(admin, enabled)
        audit(context, "network_logging", enabled.toString())
        true
    } ?: false

    fun isNetworkLoggingEnabled(context: Context): Boolean = guarded(context) { dpm, admin ->
        dpm.isNetworkLoggingEnabled(admin)
    } ?: false

    /**
     * Suspends or unsuspends [packages] at the OS level. Suspended apps can't be
     * opened and show a system dialog. Returns the packages the OS accepted.
     */
    fun setPackagesSuspended(context: Context, packages: Set<String>, suspended: Boolean): Set<String> {
        if (packages.isEmpty()) return emptySet()
        return guarded(context) { dpm, admin ->
            val failed = dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toSet()
            val applied = packages - failed
            audit(context, if (suspended) "suspend" else "unsuspend", "n=${applied.size}")
            applied
        } ?: emptySet()
    }

    /** Relinquishes device ownership (clears our policies first). Always possible — no brick. */
    fun relinquish(context: Context): Boolean = guarded(context) { dpm, admin ->
        runCatching { dpm.setNetworkLoggingEnabled(admin, false) }
        runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        audit(context, "relinquish", "device_owner")
        dpm.clearDeviceOwnerApp(context.packageName)
        true
    } ?: false

    private inline fun <T> guarded(context: Context, block: (DevicePolicyManager, ComponentName) -> T): T? {
        val dpm = dpm(context) ?: return null
        if (!dpm.isDeviceOwnerApp(context.packageName)) return null
        return try {
            block(dpm, component(context))
        } catch (e: SecurityException) {
            Log.w(TAG, "device policy call refused", e)
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "device policy call failed", e)
            null
        }
    }

    private fun audit(context: Context, what: String, detail: String) {
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "admin", what, detail), urgent = true)
    }
}
