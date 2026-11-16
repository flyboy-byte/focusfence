package app.focusfence.data

import android.content.Context

/**
 * Which enforcement strategy is active, plus its config. The strategy switch
 * exists for the single-VPN-slot problem: to use another VPN, Focus Fence's own
 * VpnService must yield the slot, and filtering falls back to device-owner
 * Private DNS lockdown so protection continues (see docs/specs/device-owner.md).
 *
 * Private DNS points at a DoT hostname. The default is a public family-filter
 * resolver (category-level only); the owner's own rules in that mode would need
 * a hosted DoT endpoint (the deferred backend). Stored in the `focusfence` file.
 */
object StrategyStore {

    enum class Strategy { LOCAL_DNS_VPN, PRIVATE_DNS }

    /** Cloudflare family filter (1.1.1.3): blocks malware + adult at the DNS layer. */
    const val DEFAULT_DOT_HOST = "family.cloudflare-dns.com"

    private const val KEY_STRATEGY = "enforcement_strategy"
    private const val KEY_DOT_HOST = "private_dns_host"
    private const val KEY_SUSPEND_APPS = "suspend_blocked_apps"
    private const val KEY_SUSPENDED = "suspended_packages"
    private const val KEY_ALWAYS_ON = "always_on_vpn"
    private const val KEY_FALLBACK = "private_dns_fallback"
    private const val KEY_FALLBACK_ACTIVE = "private_dns_fallback_active"

    fun strategy(context: Context): Strategy = runCatching {
        Strategy.valueOf(BlocklistStore.prefs(context).getString(KEY_STRATEGY, null) ?: "")
    }.getOrDefault(Strategy.LOCAL_DNS_VPN)

    fun setStrategy(context: Context, s: Strategy) {
        BlocklistStore.prefs(context).edit().putString(KEY_STRATEGY, s.name).apply()
    }

    fun privateDnsHost(context: Context): String =
        BlocklistStore.prefs(context).getString(KEY_DOT_HOST, null)?.ifBlank { null } ?: DEFAULT_DOT_HOST

    fun setPrivateDnsHost(context: Context, host: String) {
        BlocklistStore.prefs(context).edit().putString(KEY_DOT_HOST, host.trim()).apply()
    }

    fun suspendBlockedApps(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_SUSPEND_APPS, false)

    fun setSuspendBlockedApps(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_SUSPEND_APPS, on).apply()
    }

    /** Packages we have currently suspended, so a reconcile can unsuspend removed ones. */
    fun suspended(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_SUSPENDED, emptySet())?.toSet() ?: emptySet()

    fun setSuspended(context: Context, packages: Set<String>) {
        BlocklistStore.prefs(context).edit().putStringSet(KEY_SUSPENDED, packages).apply()
    }

    /** Device owner: keep our VPN as the OS always-on VPN. */
    fun alwaysOnVpn(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_ALWAYS_ON, false)

    fun setAlwaysOnVpn(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_ALWAYS_ON, on).apply()
    }

    /**
     * Device owner: whenever the VPN goes down (turned off, revoked, another VPN
     * took the slot), lock Private DNS to [privateDnsHost] until it comes back,
     * so filtering degrades to category-level instead of stopping.
     */
    fun privateDnsFallback(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_FALLBACK, true)

    fun setPrivateDnsFallback(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_FALLBACK, on).apply()
    }

    /** True while *we* set Private DNS as a fallback (so we only undo our own change). */
    fun fallbackActive(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_FALLBACK_ACTIVE, false)

    fun setFallbackActive(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_FALLBACK_ACTIVE, on).commit()
    }
}
