package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    @Test
    fun normalizeStripsSchemePathAndWww() {
        assertEquals("reddit.com", Blocklist.normalize("https://www.reddit.com/r/all?x=1"))
        assertEquals("example.com", Blocklist.normalize("  EXAMPLE.COM. "))
        assertEquals("news.ycombinator.com", Blocklist.normalize("news.ycombinator.com:443"))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(Blocklist.normalize(""))
        assertNull(Blocklist.normalize("not a domain"))
        assertNull(Blocklist.normalize("localhost"))
        assertNull(Blocklist.normalize("a..b"))
    }

    @Test
    fun frontDoorCoversWebHostsOnly() {
        assertTrue(Blocklist.isFrontDoor("facebook.com", "facebook.com"))
        assertTrue(Blocklist.isFrontDoor("www.facebook.com", "facebook.com"))
        assertTrue(Blocklist.isFrontDoor("m.facebook.com", "facebook.com"))
        assertFalse(Blocklist.isFrontDoor("graph.facebook.com", "facebook.com"))
        assertFalse(Blocklist.isFrontDoor("edge-chat.facebook.com", "facebook.com"))
        assertFalse(Blocklist.isFrontDoor("notfacebook.com", "facebook.com"))
    }

    @Test
    fun matchesExactAndSubdomains() {
        val blocked = setOf("facebook.com", "x.com")
        assertTrue(Blocklist.matches("facebook.com", blocked))
        assertTrue(Blocklist.matches("graph.facebook.com", blocked))
        assertTrue(Blocklist.matches("a.b.x.com", blocked))
        assertFalse(Blocklist.matches("notfacebook.com", blocked))
        assertFalse(Blocklist.matches("com", blocked))
        assertFalse(Blocklist.matches("wikipedia.org", blocked))
    }

    @Test
    fun allowlistExemptsAdHost() {
        val reason = Blocklist.blockReason(
            "ads.example.com", ruleDomain = null, ruleBlockedNow = false,
            allowlist = setOf("ads.example.com"), adHosts = setOf("ads.example.com"),
        )
        assertNull(reason)
    }

    @Test
    fun allowlistExemptsSubdomainOfAllowedParent() {
        val reason = Blocklist.blockReason(
            "cdn.tracker.com", ruleDomain = null, ruleBlockedNow = false,
            allowlist = setOf("tracker.com"), adHosts = setOf("cdn.tracker.com"),
        )
        assertNull(reason)
    }

    @Test
    fun allowlistDoesNotOverrideAUserRule() {
        // D-017: a user rule that is currently in force is final.
        val reason = Blocklist.blockReason(
            "reddit.com", ruleDomain = "reddit.com", ruleBlockedNow = true,
            allowlist = setOf("reddit.com"), adHosts = emptySet(),
        )
        assertEquals("reddit.com", reason)
    }

    @Test
    fun allowlistCanExemptADohGuard() {
        val reason = Blocklist.blockReason(
            "dns.google", ruleDomain = null, ruleBlockedNow = false,
            allowlist = setOf("dns.google"), adHosts = emptySet(),
        )
        assertNull(reason)
    }

    @Test
    fun emptyAllowlistChangesNothing() {
        assertEquals(
            StatsCodec.KEY_ADS,
            Blocklist.blockReason(
                "ads.example.com", ruleDomain = null, ruleBlockedNow = false,
                allowlist = emptySet(), adHosts = setOf("ads.example.com"),
            ),
        )
        assertEquals(
            StatsCodec.KEY_GUARD,
            Blocklist.blockReason(
                "dns.google", ruleDomain = null, ruleBlockedNow = false,
                allowlist = emptySet(), adHosts = emptySet(),
            ),
        )
        assertNull(
            Blocklist.blockReason(
                "wikipedia.org", ruleDomain = null, ruleBlockedNow = false,
                allowlist = emptySet(), adHosts = emptySet(),
            ),
        )
    }
}
