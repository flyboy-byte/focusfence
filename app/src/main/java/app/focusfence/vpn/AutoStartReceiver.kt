package app.focusfence.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents

/**
 * Brings the filter back after an app update or a reboot when the owner had it on, so the protection gap is
 * seconds, not "until someone opens the app". It never starts anything the owner did not already turn on, and
 * does nothing if the one-time VPN consent is gone (then only the owner can fix it, and the gap is logged).
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            else -> return
        }
        if (!VpnWanted.get(context) || DnsVpnService.isRunning) return
        val ready = VpnService.prepare(context) == null
        EventLog.activity(
            LogEvents.service(System.currentTimeMillis(), "vpn", if (ready) "autostart" else "autostart_needs_consent", why),
            urgent = true,
        )
        if (!ready) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START),
        )
    }
}
