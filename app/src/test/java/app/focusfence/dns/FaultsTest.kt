package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultsTest {
    @Test
    fun aStackKeepsClassesAndFramesButNoMessage() {
        val cause = java.io.IOException("could not open https://example.org/p/abc")
        val e = IllegalStateException("private words from a screen", cause)
        e.stackTrace = arrayOf(
            StackTraceElement("android.view.View", "draw", "View.java", 10),
            StackTraceElement("app.focusfence.sync.SyncWorker", "doWork", "SyncWorker.kt", 42),
        )
        assertEquals("IllegalStateException < IOException", Faults.exceptionChain(e))
        val at = Faults.stack(e)
        assertTrue(at, at.startsWith("IllegalStateException at android.view.View.draw:10 sync.SyncWorker.doWork:42 | IOException at "))
        assertFalse(at, at.contains("private") || at.contains("example.org"))
    }

    @Test
    fun aLongStackIsBounded() {
        val e = StackOverflowError()
        e.stackTrace = Array(5000) { StackTraceElement("app.focusfence.A", "loop", "A.kt", it) }
        assertTrue(Faults.stack(e).length < 600)
    }

    @Test
    fun connectionFailuresAreNamedByKind() {
        assertEquals("dns", Faults.netKind(java.net.UnknownHostException()))
        assertEquals("timeout", Faults.netKind(java.net.SocketTimeoutException()))
        assertEquals("connect", Faults.netKind(java.net.ConnectException()))
        assertEquals("tls", Faults.netKind(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals("io", Faults.netKind(java.io.IOException()))
        assertEquals("other", Faults.netKind(IllegalStateException()))
    }

    @Test
    fun exitsAreNamedAndCrashNotesDropped() {
        assertEquals("anr", Faults.exitReason(6))
        assertEquals("low_memory", Faults.exitReason(3))
        assertEquals("code_99", Faults.exitReason(99))
        assertNull(Faults.exitNote(4, "java.lang.IllegalStateException: something"))
        assertEquals("kill background", Faults.exitNote(13, "kill background"))
    }

    @Test
    fun errorTextLosesPathsTokensAndKeys() {
        assertEquals(
            "FileNotFoundException: https://ff.example.org/…",
            Faults.scrub("FileNotFoundException: https://ff.example.org/p/Zm9vYmFyYmF6cXV4MTIz/key?d=9"),
        )
        assertEquals("GET /p/… failed", Faults.scrub("GET /p/abcdEFGH1234/alerts?after=0 failed"))
        assertEquals("key … rejected", Faults.scrub("key Q2hhbmdlTWUxMjM0NTY3ODkwYWJjZGVm rejected"))
        // Ordinary messages and class names stay readable.
        val plain = "SocketTimeoutException: failed to connect after 15000ms (app.focusfence.sync.SyncWorker)"
        assertEquals(plain, Faults.scrub(plain))
        assertEquals(plain, LogEvents.error(0, "sync", "heartbeat", plain)["detail"])
    }

    @Test
    fun aRepeatedErrorIsWrittenOnceUntilItChangesOrAges() {
        val r = Repeats(everyMs = 1000)
        assertTrue(r.allow("sync:report", "timeout", 0))
        assertFalse(r.allow("sync:report", "timeout", 999))
        assertTrue(r.allow("sync:report", "HTTP 500", 999))
        assertTrue(r.allow("sync:shares", "HTTP 500", 999))
        assertTrue(r.allow("sync:report", "HTTP 500", 2500))
    }
}
