package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadCpuTest {

    private fun stat(tid: Int, name: String, utime: Long, stime: Long) =
        "$tid ($name) S 1 2 3 0 -1 4194368 100 0 0 0 $utime $stime 0 0 20 0 30 0 12345 0 0"

    @Test
    fun parsesIncludingAwkwardNames() {
        assertEquals(ThreadCpu.Sample(123, "RenderThread", 15), ThreadCpu.parseStat(stat(123, "RenderThread", 10, 5)))
        // Names can contain spaces and parentheses.
        assertEquals(ThreadCpu.Sample(7, "Binder:4941_2 (x)", 3), ThreadCpu.parseStat(stat(7, "Binder:4941_2 (x)", 1, 2)))
        assertNull(ThreadCpu.parseStat("garbage"))
        assertNull(ThreadCpu.parseStat("12 (short) S 1 2"))
    }

    @Test
    fun normalizesNumberedThreads() {
        assertEquals("pool-N-thread-N", ThreadCpu.normalize("pool-3-thread-12"))
        assertEquals("Binder:N_N", ThreadCpu.normalize("Binder:4941_2"))
        assertEquals("HeapTaskDaemon", ThreadCpu.normalize("HeapTaskDaemon"))
    }

    @Test
    fun deltaAggregatesAndHandlesNewAndReusedTids() {
        val prev = mapOf(
            1 to ThreadCpu.Sample(1, "main", 100),
            2 to ThreadCpu.Sample(2, "pool-1-thread-1", 10),
            3 to ThreadCpu.Sample(3, "old", 50),
        )
        val now = mapOf(
            1 to ThreadCpu.Sample(1, "main", 130),
            2 to ThreadCpu.Sample(2, "pool-1-thread-1", 15),
            4 to ThreadCpu.Sample(4, "pool-1-thread-2", 5), // new thread: counts from zero
            3 to ThreadCpu.Sample(3, "reused", 7), // tid reused by a different thread: from zero
        )
        val d = ThreadCpu.delta(prev, now, msPerTick = 10.0)
        assertEquals(300L, d["main"])
        assertEquals(100L, d["pool-N-thread-N"])
        assertEquals(70L, d["reused"])
    }

    @Test
    fun encodeTopWithOtherAndRoundTrip() {
        val ms = mapOf("a" to 50L, "b" to 30L, "c" to 10L, "d" to 5L, "z" to 0L)
        val enc = ThreadCpu.encodeTop(ms, n = 2)
        assertEquals("a=50;b=30;other=15", enc)
        assertEquals(mapOf("a" to 50L, "b" to 30L, "other" to 15L), ThreadCpu.decodeTop(enc))
    }
}
