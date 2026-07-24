package app.focusfence.data

import android.content.Context
import android.content.SharedPreferences
import app.focusfence.dns.StatsCodec

/**
 * Per-day block counters, kept in their own prefs file (they change often and
 * shouldn't churn the rules listeners). Keys: "stats_<yyyy-mm-dd>".
 */
object StatsStore {

    private const val PREFS_NAME = "focusfence_stats"
    private const val KEY_PREFIX = "stats_"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, dayKey: String): MutableMap<String, Int> =
        StatsCodec.decode(prefs(context).getString(KEY_PREFIX + dayKey, "") ?: "")

    fun add(context: Context, dayKey: String, delta: Map<String, Int>) {
        if (delta.isEmpty()) return
        val current = get(context, dayKey)
        for ((key, count) in delta) current.merge(key, count, Int::plus)
        prefs(context).edit().putString(KEY_PREFIX + dayKey, StatsCodec.encode(current)).apply()
    }

    /** Drops stored days not in [keepDayKeys]. */
    fun prune(context: Context, keepDayKeys: Collection<String>) {
        val editor = prefs(context).edit()
        var dirty = false
        for (key in prefs(context).all.keys) {
            if (key.startsWith(KEY_PREFIX) && key.removePrefix(KEY_PREFIX) !in keepDayKeys) {
                editor.remove(key)
                dirty = true
            }
        }
        if (dirty) editor.apply()
    }
}
