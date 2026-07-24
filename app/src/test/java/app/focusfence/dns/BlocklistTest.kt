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
}
