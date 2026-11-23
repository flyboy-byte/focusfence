package app.focusfence.admin

import android.content.Context
import app.focusfence.data.StrategyStore

/**
 * Seamless hand-off between the two strategies (device owner only): when the
 * local DNS VPN goes down, Private DNS is locked to the fallback host so lookups
 * stay filtered (category-level); when the VPN comes back, our fallback is undone
 * — strict Private DNS would otherwise route lookups around the tunnel.
 * Both directions are audited by [DeviceAdmin].
 */
object DnsFallback {

    fun engage(context: Context) {
        if (!DeviceAdmin.isDeviceOwner(context)) return
        if (!StrategyStore.privateDnsFallback(context)) return
        if (StrategyStore.strategy(context) != StrategyStore.Strategy.LOCAL_DNS_VPN) return
        if (DeviceAdmin.setPrivateDns(context, StrategyStore.privateDnsHost(context))) {
            StrategyStore.setFallbackActive(context, true)
        }
    }

    fun release(context: Context) {
        if (!StrategyStore.fallbackActive(context)) return
        if (!DeviceAdmin.isDeviceOwner(context) || DeviceAdmin.clearPrivateDns(context)) {
            StrategyStore.setFallbackActive(context, false)
        }
    }
}
