package app.focusfence.admin

import android.content.Context
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.StrategyStore

/**
 * Reconciles OS-level package suspension (device-owner "teeth" for app blocking)
 * with the blocked-apps set. Suspension is a strengthening and always allowed;
 * unsuspending happens only for apps removed from the blocked set (which is the
 * guarded UNBLOCK_APP action in the UI). When managed mode or the setting is
 * off, it unsuspends anything it had suspended, so turning the feature off never
 * leaves an app stuck suspended.
 */
object AppSuspension {

    /** Safe to call often; a no-op when nothing changed or not device owner. */
    fun reconcile(context: Context) {
        if (!DeviceAdmin.isDeviceOwner(context)) return
        val want = if (StrategyStore.suspendBlockedApps(context)) BlockedAppsStore.get(context) else emptySet()
        val have = StrategyStore.suspended(context)
        val toSuspend = want - have
        val toUnsuspend = have - want
        if (toSuspend.isEmpty() && toUnsuspend.isEmpty()) return
        val suspended = DeviceAdmin.setPackagesSuspended(context, toSuspend, true)
        DeviceAdmin.setPackagesSuspended(context, toUnsuspend, false)
        StrategyStore.setSuspended(context, (have + suspended) - toUnsuspend)
    }
}
