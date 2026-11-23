package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {

    @Test
    fun scoresCompactSortedAndRounded() {
        val s = Signals.scoresCompact(mapOf("neutral" to 0.05, "porn" to 0.9123, "sexy" to 0.0377))
        assertEquals("porn=0.912,neutral=0.050,sexy=0.038", s)
    }

    @Test
    fun buckets() {
        assertEquals(0, Signals.bucket(0.0))
        assertEquals(0, Signals.bucket(0.099))
        assertEquals(7, Signals.bucket(0.70))
        assertEquals(9, Signals.bucket(0.95))
        assertEquals(9, Signals.bucket(1.0))
        assertEquals(0, Signals.bucket(-0.1))
    }

    @Test
    fun findsHostsInText() {
        val text = "Check https://www.Bet365.com/promo and also casino.example.org! email me@mail.com " +
            "see photo.png or notes.txt, e.g. this, version 1.2.3, sub.domain.co.uk."
        val hosts = Signals.hostsIn(text)
        assertTrue(hosts.toString(), "www.bet365.com" in hosts)
        assertTrue(hosts.toString(), "casino.example.org" in hosts)
        assertTrue(hosts.toString(), "sub.domain.co.uk" in hosts)
        // Email domain is preceded by '@' — not a visited/mentioned site in the same sense.
        assertTrue(hosts.toString(), "mail.com" !in hosts)
        assertTrue(hosts.toString(), "photo.png" !in hosts && "notes.txt" !in hosts)
        assertTrue(hosts.toString(), hosts.none { it.startsWith("e.g") || it.startsWith("1.2") })
    }

    @Test
    fun hostsCappedAndDeduped() {
        val text = (1..50).joinToString(" ") { "site$it.com site$it.com" }
        val hosts = Signals.hostsIn(text, max = 10)
        assertEquals(10, hosts.size)
        assertEquals(hosts.distinct(), hosts)
    }

    @Test
    fun categorizeAgainstBundles() {
        val lists = mapOf("gambling" to setOf("bet365.com"), "adult" to setOf("bad.example"))
        val hits = Signals.categorize(listOf("www.bet365.com", "news.org", "bad.example"), lists)
        assertEquals(listOf("gambling"), hits["www.bet365.com"])
        assertEquals(listOf("adult"), hits["bad.example"])
        assertTrue("news.org" !in hits)
        assertEquals("www.bet365.com:gambling;bad.example:adult", Signals.encodeHits(hits))
        assertEquals(listOf("adult", "gambling"), Signals.categoriesOf(hits))
        assertTrue(Signals.categorize(listOf("x.com"), emptyMap()).isEmpty())
    }
}
