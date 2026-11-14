package app.focusfence.data

import android.content.Context

/**
 * What the activity log records. Kept in the main `focusfence` prefs file (not
 * the log's own file) because the services depend on these at runtime and the
 * prefs-change listener on that file is the only propagation mechanism: the
 * VPN service re-reads [KEY_LOG_ALLOWED_DNS], the accessibility service
 * re-scopes its event filter on [KEY_LOG_URLS].
 */
object LogSettings {

    /** Log allowed DNS queries too, not only blocked ones. Detection needs this, so on by default. */
    const val KEY_LOG_ALLOWED_DNS = "log_allowed_dns"

    /**
     * Record browser addresses. Off by default: it widens the accessibility
     * service to every known browser and to content-changed events, which is a
     * real battery/privacy cost the owner should opt into knowingly.
     */
    const val KEY_LOG_URLS = "log_urls"

    fun logAllowedDns(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_LOG_ALLOWED_DNS, true)

    fun setLogAllowedDns(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_LOG_ALLOWED_DNS, on).apply()
    }

    fun logUrls(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_LOG_URLS, false)

    fun setLogUrls(context: Context, on: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_LOG_URLS, on).apply()
    }
}
