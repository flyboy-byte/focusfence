package app.focusfence.data

import android.content.Context
import app.focusfence.dns.Upstreams

/**
 * The owner's choice of resolver for allowed lookups (see [Upstreams]). Lives in
 * the `focusfence` prefs file so the VPN service's prefs listener picks changes
 * up live — that listener is the only propagation path.
 */
object DnsUpstreamStore {
    const val KEY_MODE = "dns_upstream_mode"
    const val KEY_PRESET = "dns_upstream_preset"
    const val KEY_CUSTOM = "dns_upstream_custom"
    const val KEY_FALLBACK = "dns_upstream_fallback"
    /**
     * Bumped on every save and written last. SharedPreferences only notifies on
     * *changed* values, so the service listens to this, not to the fields.
     */
    const val KEY_REV = "dns_upstream_rev"

    fun load(context: Context): Upstreams.Config {
        val p = BlocklistStore.prefs(context)
        return Upstreams.fromPrefs(
            p.getString(KEY_MODE, null), p.getString(KEY_PRESET, null),
            p.getString(KEY_CUSTOM, null), p.getBoolean(KEY_FALLBACK, true),
        )
    }

    fun customText(context: Context): String = BlocklistStore.prefs(context).getString(KEY_CUSTOM, "").orEmpty()

    fun save(context: Context, mode: Upstreams.Mode, presetId: String?, customText: String, fallback: Boolean) {
        BlocklistStore.prefs(context).edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_PRESET, presetId)
            .putString(KEY_CUSTOM, customText)
            .putBoolean(KEY_FALLBACK, fallback)
            .putLong(KEY_REV, System.nanoTime())
            .apply()
    }
}
