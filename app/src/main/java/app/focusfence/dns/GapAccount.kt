package app.focusfence.dns

/**
 * What happened while the phone couldn't check in (D-036 follow-up, 2026-11-25). Sent with the first check-in after a
 * long gap, so the server's "checking in again" message can say why the phone went quiet instead of leaving partners
 * to guess between a dead zone and protection being turned off.
 *
 * Built from the phone's own logs over the gap: activity records (filter on/off through [Digest], restarts, missing
 * sequence numbers) and the diag `net` samples (taken at screen on/off and battery changes, so coarse). Pure, so it
 * is tested on the JVM. Like every alert, it is the app's own account: the server can prove which phone sent it, not
 * that a modified app told the truth.
 */
class GapAccount(private val fromT: Long) {
    // Streaming: a gap of days can cover 100k+ records, so nothing is kept but counters.
    private val digest = Digest(fromT)
    private var restarted = false
    private var online = 0
    private var offline = 0

    /** An activity record. Only svc/integ records need their fields; others need just `t` and `seq`. */
    fun addActivity(f: Map<String, Any?>) {
        digest.add(f)
        if ((f["t"] as? Long ?: 0) >= fromT && f["type"] == LogEvents.TYPE_SERVICE && f["comp"] == "device" && f["state"] == "rebooted") restarted = true
    }

    /** A diag `net` sample. */
    fun addNet(f: Map<String, Any?>) {
        if ((f["t"] as? Long ?: 0) < fromT) return
        if (f["tr"] != "none" && f["val"] == true) online++ else offline++
    }

    fun describe(toT: Long): String {
        val d = digest.build(toT)
        val offMin = d["off_min"] as? Long ?: 0L
        val missing = d["missing"] as? Long ?: 0L
        val parts = ArrayList<String>()
        parts += when {
            online + offline == 0 -> "no record of its connection"
            online == 0 -> "the phone had no internet"
            offline > 0 -> "the phone had no internet for part of it"
            else -> "the phone had internet, but its check-ins didn't arrive"
        }
        if (restarted) parts += "it was switched off or restarted"
        parts += if (offMin > 0) "protection was OFF for $offMin minutes" else "protection stayed on the whole time"
        if (missing > 0) parts += "$missing records are missing from its log"
        return "While it was quiet: " + parts.joinToString("; ") + "."
    }

    companion object {
        /** Gaps shorter than this are normal sync jitter and get no account. */
        const val MIN_GAP_MS = 30 * 60_000L

        fun describe(activity: List<Map<String, Any?>>, net: List<Map<String, Any?>>, fromT: Long, toT: Long): String =
            GapAccount(fromT).apply { activity.forEach(::addActivity); net.forEach(::addNet) }.describe(toT)
    }
}
