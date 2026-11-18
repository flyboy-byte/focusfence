package app.focusfence.data

import android.content.Context

/**
 * What the Monitoring screen needs to say whether capture works: the last
 * screenshot and running totals. Its own prefs file, so a write per capture
 * never wakes the `focusfence` rule listeners.
 */
object CaptureStats {
    private const val FILE = "focusfence_capture"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun recordShot(c: Context, t: Long, pkg: String, label: String?, score: Double?, flagged: Boolean) {
        val p = prefs(c)
        p.edit()
            .putLong("last_t", t)
            .putString("last_pkg", pkg)
            .putString("last_label", label)
            .putString("last_score", score?.toString())
            .putBoolean("last_flagged", flagged)
            .putLong("taken", p.getLong("taken", 0) + 1)
            .putLong("flagged", p.getLong("flagged", 0) + if (flagged) 1 else 0)
            .apply()
    }

    fun recordFailure(c: Context) {
        val p = prefs(c)
        p.edit().putLong("failed", p.getLong("failed", 0) + 1).apply()
    }

    fun setAiLoadFailed(c: Context, failed: Boolean) {
        prefs(c).edit().putBoolean("ai_load_failed", failed).apply()
    }

    data class Snapshot(
        val lastAt: Long, val lastPkg: String?, val lastLabel: String?, val lastScore: Double?,
        val lastFlagged: Boolean, val taken: Long, val flagged: Long, val failed: Long, val aiLoadFailed: Boolean,
    )

    fun snapshot(c: Context): Snapshot {
        val p = prefs(c)
        return Snapshot(
            p.getLong("last_t", 0), p.getString("last_pkg", null), p.getString("last_label", null),
            p.getString("last_score", null)?.toDoubleOrNull(), p.getBoolean("last_flagged", false),
            p.getLong("taken", 0), p.getLong("flagged", 0), p.getLong("failed", 0), p.getBoolean("ai_load_failed", false),
        )
    }
}
