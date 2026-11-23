package app.focusfence.dns

/**
 * One battery/CPU observation. [cpuMs] is this process's own CPU time
 * (android.os.Process.getElapsedCpuTime) and is only comparable between two
 * samples with the same [pid]; [uptimeMs] is SystemClock.elapsedRealtime, which
 * restarts at boot, so a decrease means the phone rebooted in between.
 */
data class BatterySample(
    val t: Long,
    val levelPct: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
    val cpuMs: Long,
    val uptimeMs: Long,
    val pid: Int,
    /** Remaining charge in µAh (BATTERY_PROPERTY_CHARGE_COUNTER); finer than %, not on all devices. */
    val chargeUah: Long? = null,
    /** Instantaneous current in µA (BATTERY_PROPERTY_CURRENT_NOW); sign convention varies by OEM. */
    val currentUa: Long? = null,
    val tempDeciC: Int? = null,
) {
    companion object {
        fun fromLog(f: Map<String, Any?>): BatterySample? {
            if (f["type"] != LogEvents.TYPE_BATTERY) return null
            return BatterySample(
                t = (f["t"] as? Long) ?: return null,
                levelPct = ((f["level"] as? Long) ?: return null).toInt(),
                plugged = (f["plugged"] as? Boolean) ?: return null,
                screenOn = (f["screen"] as? Boolean) ?: return null,
                cpuMs = (f["cpu"] as? Long) ?: return null,
                uptimeMs = (f["up"] as? Long) ?: return null,
                pid = ((f["pid"] as? Long) ?: return null).toInt(),
                chargeUah = f["uah"] as? Long,
                currentUa = f["ua"] as? Long,
                tempDeciC = (f["temp"] as? Long)?.toInt(),
            )
        }
    }
}

/**
 * When to record a sample. Samples are taken only from broadcasts the system
 * sends anyway (battery changed, screen on/off) — never from a timer — so
 * measuring adds no wakeups of its own. ACTION_BATTERY_CHANGED can fire many
 * times per minute on some devices (voltage/temperature jitter), hence the
 * throttle: only record on a meaningful change or after [maxGapMs].
 */
object BatteryPolicy {
    const val DEFAULT_MAX_GAP_MS = 15 * 60_000L

    fun shouldSample(
        prev: BatterySample?,
        levelPct: Int,
        plugged: Boolean,
        screenOn: Boolean,
        now: Long,
        maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    ): Boolean {
        if (prev == null) return true
        return levelPct != prev.levelPct || plugged != prev.plugged ||
            screenOn != prev.screenOn || now - prev.t >= maxGapMs
    }
}

/**
 * Drain attributed to screen-on vs screen-off time, from consecutive sample
 * pairs. A pair counts only if neither end is plugged in, there was no reboot
 * between them, and they are at most [maxPairGapMs] apart (a long gap means
 * the service wasn't running, so nothing is known about that span). Each pair
 * is attributed to the screen state at its start — screen changes trigger a
 * sample, so a pair never spans a change while sampling is running.
 */
object BatterySummary {

    data class Bucket(
        val hours: Double,
        val pctDrop: Int,
        val uahDrop: Long?,
        val cpuMs: Long,
    ) {
        val pctPerHour: Double get() = if (hours > 0) pctDrop / hours else 0.0
        val cpuMsPerHour: Double get() = if (hours > 0) cpuMs / hours else 0.0
    }

    data class Result(
        val screenOn: Bucket,
        val screenOff: Bucket,
        /** Hours covered by samples but excluded (charging, reboots, gaps). */
        val excludedHours: Double,
        val samples: Int,
    )

    const val DEFAULT_MAX_PAIR_GAP_MS = 2 * 60 * 60_000L

    fun summarize(samples: List<BatterySample>, maxPairGapMs: Long = DEFAULT_MAX_PAIR_GAP_MS): Result {
        val sorted = samples.sortedBy { it.t }
        val acc = arrayOf(Acc(), Acc()) // [off, on]
        var excludedMs = 0L
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val dt = b.t - a.t
            if (dt <= 0) continue
            val usable = !a.plugged && !b.plugged && b.uptimeMs >= a.uptimeMs && dt <= maxPairGapMs
            if (!usable) {
                excludedMs += dt
                continue
            }
            val bucket = acc[if (a.screenOn) 1 else 0]
            bucket.ms += dt
            bucket.pct += a.levelPct - b.levelPct
            if (a.chargeUah != null && b.chargeUah != null) {
                bucket.uah += a.chargeUah - b.chargeUah
                bucket.uahPairs++
            } else {
                bucket.uahMissing = true
            }
            if (a.pid == b.pid && b.cpuMs >= a.cpuMs) bucket.cpu += b.cpuMs - a.cpuMs
        }
        return Result(acc[1].toBucket(), acc[0].toBucket(), excludedMs / 3_600_000.0, sorted.size)
    }

    private class Acc {
        var ms = 0L
        var pct = 0
        var uah = 0L
        var uahPairs = 0
        var uahMissing = false
        var cpu = 0L

        fun toBucket() = Bucket(
            hours = ms / 3_600_000.0,
            pctDrop = pct,
            uahDrop = if (uahPairs > 0 && !uahMissing) uah else null,
            cpuMs = cpu,
        )
    }
}

/**
 * Accumulates "is it working" counters and resolver latencies between diag
 * perf records. Thread-safe; [snapshot] returns the window and resets it.
 * Latencies are kept in a bounded buffer — beyond [maxSamples] per window the
 * percentiles come from the first [maxSamples] queries, which is plenty at
 * phone query rates and keeps memory fixed.
 */
class PerfWindow(private val maxSamples: Int = 4096, private val startedAt: Long) {

    private val counts = LinkedHashMap<String, Long>()
    private val latencies = LongArray(maxSamples)
    private var latencyCount = 0
    private var windowStart = startedAt

    @Synchronized
    fun count(key: String, delta: Long = 1) {
        counts[key] = (counts[key] ?: 0L) + delta
    }

    @Synchronized
    fun latency(ms: Long) {
        if (latencyCount < maxSamples) latencies[latencyCount++] = ms
        counts["lat_n"] = (counts["lat_n"] ?: 0L) + 1
    }

    /** Returns (windowMs, fields) and starts a new window at [now]. */
    @Synchronized
    fun snapshot(now: Long): Pair<Long, Map<String, Any?>> {
        val fields = LinkedHashMap<String, Any?>(counts)
        if (latencyCount > 0) {
            val sorted = latencies.copyOf(latencyCount).also { it.sort() }
            fields["p50"] = percentile(sorted, 0.50)
            fields["p95"] = percentile(sorted, 0.95)
            fields["p99"] = percentile(sorted, 0.99)
            fields["max"] = sorted.last()
        }
        val window = now - windowStart
        counts.clear()
        latencyCount = 0
        windowStart = now
        return window to fields
    }

    @Synchronized
    fun isEmpty(): Boolean = counts.isEmpty()

    companion object {
        /** Nearest-rank percentile of an ascending array. */
        fun percentile(sorted: LongArray, p: Double): Long {
            require(sorted.isNotEmpty())
            val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
