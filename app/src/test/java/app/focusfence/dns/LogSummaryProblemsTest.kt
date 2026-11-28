package app.focusfence.dns

import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummaryProblemsTest {
    @Test
    fun problemsComeFirstAndSayWhatBroke() {
        val s = LogSummary()
        s.add(LogEvents.crash(1, "main", "NullPointerException", "NullPointerException at sync.SyncWorker.doWork:42"))
        s.add(LogEvents.exit(2, "anr", 0, 125, 1000, null))
        s.add(LogEvents.net(3, "cell", true, false, true, 1, 1, null, "up"))
        s.add(LogEvents.net(4, "cell", true, false, true, 1, 1, null))
        s.add(LogEvents.error(5, "update", "check", "x"))
        s.add(LogEvents.perf(6, 1000, mapOf("fwd_err_udp_timeout" to 4L, "http_fail_dns" to 2L)))
        val text = s.render()
        for (line in listOf(
            "crashes       1", "sync.SyncWorker.doWork:42", "app stopped   anr=1", "errors        update:check=1",
            "network       up=1", "dns failures  udp_timeout=4", "server fails  dns=2",
        )) assertTrue("$line missing in\n$text", text.contains(line))
        assertTrue(text.indexOf("app & connection problems") < text.indexOf("== battery"))
    }
}
