package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class HedgedTest {
    private val ex = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private fun slow(ms: Long, v: String?): () -> String? = { Thread.sleep(ms); v }

    @Test fun fastFirstAttemptWinsAndNothingElseIsStarted() {
        var hedged = 0
        val w = Hedged.race(listOf(slow(10, "a"), slow(0, "b")), 200, ex, onHedge = { hedged++ })!!
        assertEquals(0, w.index); assertEquals("a", w.value); assertEquals(0, hedged)
    }

    @Test fun slowFirstIsHedgedAndTheFasterSecondWins() {
        var hedged = 0
        val t0 = System.nanoTime()
        val w = Hedged.race(listOf(slow(1500, "slow"), slow(10, "fast")), 100, ex, onHedge = { hedged++ })!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("fast", w.value); assertEquals(1, w.index); assertEquals(1, hedged)
        assertTrue("took $ms ms, not the 1.5 s timeout", ms < 800)
    }

    @Test fun aFailedAttemptStartsTheNextAtOnceNotAfterTheHedgeDelay() {
        var fails = 0
        val t0 = System.nanoTime()
        val w = Hedged.race(listOf<() -> String?>({ null }, slow(5, "ok")), 5_000, ex, onFail = { fails++ })!!
        assertEquals("ok", w.value); assertEquals(1, fails)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    @Test fun throwingAttemptsCountAsFailuresAndAllFailingGivesNull() {
        var fails = 0
        assertNull(Hedged.race(listOf<() -> String?>({ error("x") }, { null }), 50, ex, onFail = { fails++ }))
        assertEquals(2, fails)
    }

    @Test fun emptyListIsNull() = assertNull(Hedged.race(emptyList<() -> String?>(), 50, ex))
}
