package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEventsTest {

    @Test
    fun dnsRecordCarriesRuleEvenWhenAllowed() {
        val rule = BlockRule("reddit.com", budgetMinutes = 20)
        val f = LogEvents.dns(10L, "www.reddit.com", 1, LogEvents.DECISION_ALLOWED, rule = rule)
        assertEquals("allowed", f["decision"])
        assertEquals("reddit.com", f["rule"])
        assertEquals("budget", f["mode"])
        assertEquals("A", f["qtype"])
        assertFalse(f.containsKey("reason"))
    }

    @Test
    fun ruleModes() {
        assertEquals("always", LogEvents.ruleMode(BlockRule("a.com")))
        assertEquals("window", LogEvents.ruleMode(BlockRule("a.com", 60, 120)))
        assertEquals("budget", LogEvents.ruleMode(BlockRule("a.com", budgetMinutes = 5)))
        assertEquals("section", LogEvents.ruleMode(BlockRule("a.com", allowPaths = listOf("/x"))))
    }

    @Test
    fun qtypeNamesFallBackToNumber() {
        assertEquals("AAAA", LogEvents.qtypeName(28))
        assertEquals("HTTPS", LogEvents.qtypeName(65))
        assertEquals("99", LogEvents.qtypeName(99))
    }

    @Test
    fun splitUrlDropsQueryFragmentSchemeAndPort() {
        assertEquals(
            "www.reddit.com" to "/r/android",
            LogEvents.splitUrl("https://www.Reddit.com:443/r/android?utm=x#top"),
        )
        assertEquals("example.com" to "/", LogEvents.splitUrl("example.com"))
        assertEquals("example.com" to "/", LogEvents.splitUrl("example.com?q=secret"))
        assertNull(LogEvents.splitUrl("how to fix brakes")) // typed search, not a URL
        assertNull(LogEvents.splitUrl("localhost"))
    }

    @Test
    fun errorDetailIsBounded() {
        val f = LogEvents.error(1L, "vpn", "x", "y".repeat(1000))
        assertEquals(300, (f["detail"] as String).length)
    }

    @Test
    fun deduperCollapsesRepeatsOnly() {
        val d = UrlDeduper()
        assertTrue(d.accept("chrome", "a.com", "/"))
        assertFalse(d.accept("chrome", "a.com", "/"))
        assertTrue(d.accept("chrome", "a.com", "/b"))
        assertTrue(d.accept("firefox", "a.com", "/b"))
        assertTrue(d.accept("firefox", "a.com", "/")) // navigating back is a new visit
    }
}
