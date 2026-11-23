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
 * Thin, fail-safe wrapper over [DevicePolicyManager] for the optional managed
 * ("teeth") mode. Every call no-ops and returns false when the app is not the
 * device owner or the OS refuses, so nothing here can throw into normal flows.
 *
 * Powers used (device-owner only), all in service of detect-and-notify plus the
 * strategy switch — never uninstall blocking:
 *  - Private DNS lockdown (frees the VPN slot while filtering continues),
 *  - network logging (package-attributed DNS, fills the "which app" gap),
 *  - package suspension (OS-level app blocking, stronger than the a11y bounce),
 *  - always-on VPN (the OS starts our tunnel at boot and restarts it), and
 *  - locking the VPN / Private DNS settings so neither can be flipped from
 *    system Settings — strict Private DNS would route lookups around the tunnel.
 * Lockdown ("block connections without VPN") is deliberately never used: our
 * tunnel routes only DNS, so lockdown would cut the phone off whenever the VPN
 * restarts, and the stance is friction, not a brick.
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
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@guarded false // API 29+; lint caught the unguarded call
        val code = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
        val ok = code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        audit(context, "private_dns_set", if (ok) host else "error:$code")
        ok
    } ?: false

    /** Returns Private DNS to automatic (opportunistic), undoing our host. */
    fun clearPrivateDns(context: Context): Boolean = guarded(context) { dpm, admin ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@guarded false
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

    /** Makes our VPN the always-on VPN (no lockdown), or clears it. */
    fun setAlwaysOnVpn(context: Context, on: Boolean): Boolean = guarded(context) { dpm, admin ->
        val ok = try {
            dpm.setAlwaysOnVpnPackage(admin, if (on) context.packageName else null, false)
            true
        } catch (e: Exception) {
            // NameNotFound / UnsupportedOperation (VPN not always-on capable).
            Log.w(TAG, "always-on VPN refused", e)
            false
        }
        audit(context, "always_on_vpn", if (ok) on.toString() else "error")
        ok
    } ?: false

    fun isAlwaysOnVpnOurs(context: Context): Boolean = guarded(context) { dpm, admin ->
        dpm.getAlwaysOnVpnPackage(admin) == context.packageName
    } ?: false

    private val settingsRestrictions: List<String>
        get() = buildList {
            add(UserManager.DISALLOW_CONFIG_VPN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }

    /** Locks (or unlocks) the system VPN and Private DNS settings screens. */
    fun setNetworkSettingsLocked(context: Context, locked: Boolean): Boolean = guarded(context) { dpm, admin ->
        for (r in settingsRestrictions) {
            if (locked) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
        }
        audit(context, "network_settings_lock", locked.toString())
        true
    } ?: false

    fun isNetworkSettingsLocked(context: Context): Boolean = guarded(context) { dpm, admin ->
        dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
    } ?: false

    /** Relinquishes device ownership (clears our policies first). Always possible — no brick. */
    fun relinquish(context: Context): Boolean = guarded(context) { dpm, admin ->
        runCatching { dpm.setNetworkLoggingEnabled(admin, false) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        for (r in settingsRestrictions) runCatching { dpm.clearUserRestriction(admin, r) }
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
