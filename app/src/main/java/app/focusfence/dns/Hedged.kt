package app.focusfence.dns

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Tries several ways to answer one DNS query, starting the next one if the current one has not answered within
 * [hedgeMs] (or has already failed), and takes whichever answers first. The sequential loop it replaces waited out a
 * full timeout per dead resolver (1.5 s for each of the network's two DNS servers, then DoH): in the 2026-11-23 export
 * that made a morning of p95 lookups at 3 to 7 s on a carrier whose UDP DNS was dropping packets. Pure, so it is tested
 * on the JVM; the caller supplies the executor.
 */
object Hedged {
    class Win<T>(val index: Int, val value: T)

    /**
     * Runs [attempts] in order, hedged. Returns the first non-null result, or null once every attempt has finished
     * without one. An attempt that throws counts as null. [onFail] is called once per failed attempt; [onHedge] once
     * for each attempt started while an earlier one was still outstanding.
     */
    fun <T : Any> race(
        attempts: List<() -> T?>,
        hedgeMs: Long,
        executor: Executor,
        onFail: () -> Unit = {},
        onHedge: () -> Unit = {},
    ): Win<T>? {
        if (attempts.isEmpty()) return null
        val results = LinkedBlockingQueue<Pair<Int, T?>>()
        var launched = 0
        var finished = 0
        fun launch() {
            val i = launched++
            executor.execute { results.put(i to runCatching { attempts[i]() }.getOrNull()) }
        }
        launch()
        while (finished < launched || launched < attempts.size) {
            val r = if (launched < attempts.size) results.poll(hedgeMs, TimeUnit.MILLISECONDS) else results.take()
            if (r == null) { // the current attempt is slow: start the next one beside it
                onHedge(); launch(); continue
            }
            finished++
            if (r.second != null) return Win(r.first, r.second!!)
            onFail()
            if (launched < attempts.size) launch() // failed outright: next one now, not after the hedge delay
        }
        return null
    }
}
