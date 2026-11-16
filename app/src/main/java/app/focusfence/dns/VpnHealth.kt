package app.focusfence.dns

/**
 * Decides whether the running DNS filter is actually working, from what the
 * service observes: upstream relay outcomes, query arrivals, screen state and
 * the Private DNS mode. Pure (no Android types) so the thresholds are tested.
 *
 * "VPN is up" is not "filtering works". The failure modes this catches:
 *  - [State.BYPASSED]: Private DNS is in strict (hostname) mode. Android then
 *    sends DoT straight to that host, outside our tunnel, so the filter and the
 *    activity log see nothing while the VPN looks healthy.
 *  - [State.SILENT]: screen on for a while and not one query reached the tunnel
 *    — some other route is resolving names (or the tunnel is wedged).
 *  - [State.UPSTREAM_DOWN]: a run of relays all failed while the phone is online,
 *    so allowed sites don't load.
 *  - [State.DEGRADED]: a high failure share over the recent window (slow/flaky).
 * Failures while there's no non-VPN network are [State.OFFLINE], not an alarm.
 */
class VpnHealth(
    private val window: Int = 40,
    private val minSamples: Int = 20,
    private val degradedRate: Double = 0.25,
    private val downStreak: Int = 6,
    private val silenceMillis: Long = 10 * 60_000L,
) {

    enum class State(val key: String, val problem: Boolean) {
        OK("ok", false),
        OFFLINE("offline", false),
        DEGRADED("degraded", true),
        UPSTREAM_DOWN("upstream_down", true),
        SILENT("silent", true),
        BYPASSED("bypassed", true),
    }

    private val outcomes = BooleanArray(window)
    private var count = 0
    private var next = 0
    private var failStreak = 0
    private var lastQueryAt = 0L
    private var screenOnSince = 0L
    private var screenOn = true
    private var startedAt = 0L

    @Synchronized
    fun reset(now: Long, screenOn: Boolean) {
        count = 0
        next = 0
        failStreak = 0
        lastQueryAt = 0L
        startedAt = now
        this.screenOn = screenOn
        screenOnSince = now
    }

    @Synchronized
    fun recordQuery(now: Long) {
        lastQueryAt = now
    }

    @Synchronized
    fun recordScreen(on: Boolean, now: Long) {
        if (on && !screenOn) screenOnSince = now
        screenOn = on
    }

    @Synchronized
    fun recordForward(ok: Boolean) {
        outcomes[next] = ok
        next = (next + 1) % window
        if (count < window) count++
        failStreak = if (ok) 0 else failStreak + 1
    }

    /** Failure share over the recent window, or null with too few samples. */
    @Synchronized
    fun failureRate(): Double? {
        if (count < minSamples) return null
        var fails = 0
        for (i in 0 until count) if (!outcomes[i]) fails++
        return fails.toDouble() / count
    }

    /**
     * [online] is only consulted when relays are failing (it costs a system
     * call); [privateDnsStrict] is true when Private DNS is in hostname mode.
     */
    fun evaluate(now: Long, privateDnsStrict: Boolean, online: () -> Boolean): State {
        if (privateDnsStrict) return State.BYPASSED
        val (silent, down, rate) = synchronized(this) {
            val quietSince = maxOf(lastQueryAt, screenOnSince, startedAt)
            Triple(
                screenOn && now - quietSince >= silenceMillis,
                failStreak >= downStreak,
                failureRate(),
            )
        }
        if (silent) return State.SILENT
        val degraded = rate != null && rate >= degradedRate
        if (!down && !degraded) return State.OK
        if (!online()) return State.OFFLINE
        return if (down) State.UPSTREAM_DOWN else State.DEGRADED
    }
}
