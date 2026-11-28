package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The log format is locked (docs/specs/event-log.md). Exports from different versions are read by the same tools, so a
 * field is only ever added. If this fails because a name was removed or changed, put it back; if a field was added, add
 * it to the end of its list here and to the spec.
 */
class LogSchemaTest {
    private fun check(expected: String, record: Map<String, Any?>) = assertEquals(expected, record.keys.joinToString(" "))

    private val rule = BlockRule.decode("example.com")!!

    @Test
    fun activityRecords() {
        check("t type host qtype decision reason cat pkg rule mode",
            LogEvents.dns(1, "example.com", 1, "blocked", "rule", rule, listOf("adult"), "p"))
        check("t type pkg host path cat", LogEvents.url(1, "p", "h", "/", "adult"))
        check("t type pkg state", LogEvents.app(1, "p", "fg"))
        check("t type state", LogEvents.screen(1, "on"))
        check("t type pkg text hits cat terms", LogEvents.text(1, "p", "x", "h", "c", "t"))
        check("t type pkg score label kept id model scores flagged ms trigger same body regions",
            LogEvents.shot(1, "p", 0.1, "l", false, "i", "m", "s", false, 1, "timer", false, "b", "r"))
        check("t type shot_t pkg label pct verdict", LogEvents.review(1, 1, "p", "l", 1, "ok"))
        check("t type action pkg host path", LogEvents.enforce(1, "app_home", "p", "h", "/"))
        check("t type comp state detail", LogEvents.service(1, "vpn", "start", "d"))
        check("t type key value was", LogEvents.integrity(1, "vpn", "running", null))
    }

    @Test
    fun diagRecords() {
        check("t type level plugged screen cpu up pid uah ua temp",
            LogEvents.battery(1, BatterySample(1, 50, false, true, 1, 1, 1, 1, 1, 1)))
        check("t type win q", LogEvents.perf(1, 1, mapOf("q" to 1L)))
        check("t type top total screen", LogEvents.threads(1, "a=1", 1, true))
        check("t type tr val roam metered down up sig ev", LogEvents.net(1, "wifi", true, false, false, 1, 1, -60, "up"))
        check("t type comp what detail", LogEvents.error(1, "c", "w", "d"))
        check("t type thread ex at", LogEvents.crash(1, "main", "E", "E at a.b:1"))
        check("t type reason status importance rss_kb note", LogEvents.exit(1, "anr", 0, 100, 1, "n"))
    }

    @Test
    fun typeNamesAndVersion() {
        assertEquals(1, LogEvents.SCHEMA_VERSION)
        assertEquals(
            "dns url app screen text shot rev enforce svc integ bat perf thr net err meta crash exit",
            listOf(
                LogEvents.TYPE_DNS, LogEvents.TYPE_URL, LogEvents.TYPE_APP, LogEvents.TYPE_SCREEN, LogEvents.TYPE_TEXT,
                LogEvents.TYPE_SHOT, LogEvents.TYPE_REVIEW, LogEvents.TYPE_ENFORCE, LogEvents.TYPE_SERVICE,
                LogEvents.TYPE_INTEGRITY, LogEvents.TYPE_BATTERY, LogEvents.TYPE_PERF, LogEvents.TYPE_THREADS,
                LogEvents.TYPE_NET, LogEvents.TYPE_ERROR, LogEvents.TYPE_META, LogEvents.TYPE_CRASH, LogEvents.TYPE_EXIT,
            ).joinToString(" "),
        )
    }
}
