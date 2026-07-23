package app.focusfence.data

import android.content.Context

/** Persists per-domain daily usage so budgets survive VPN restarts and reboots. */
object UsageStore {

    private fun key(domain: String) = "usage_$domain"

    fun save(context: Context, domain: String, dayKey: String, usedMillis: Long) {
        BlocklistStore.prefs(context).edit().putString(key(domain), "$dayKey|$usedMillis").apply()
    }

    /** Returns (dayKey, usedMillis) or null if nothing is stored. */
    fun load(context: Context, domain: String): Pair<String, Long>? {
        val raw = BlocklistStore.prefs(context).getString(key(domain), null) ?: return null
        val sep = raw.lastIndexOf('|')
        if (sep <= 0) return null
        val millis = raw.substring(sep + 1).toLongOrNull() ?: return null
        return raw.substring(0, sep) to millis
    }

    fun usedMillisOn(context: Context, domain: String, dayKey: String): Long {
        val (storedDay, millis) = load(context, domain) ?: return 0L
        return if (storedDay == dayKey) millis else 0L
    }
}
