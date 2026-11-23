package app.focusfence.dns

import app.focusfence.dns.ReviewPolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    @Test fun keepsOnlyAtOrAboveThresholdWhenEnabled() {
        assertTrue(ReviewPolicy.shouldKeep(true, 0.50, 50))
        assertTrue(ReviewPolicy.shouldKeep(true, 0.82, 50))
        assertFalse(ReviewPolicy.shouldKeep(true, 0.49, 50))
        assertFalse(ReviewPolicy.shouldKeep(false, 0.99, 50))
        assertFalse(ReviewPolicy.shouldKeep(true, null, 50))
    }

    @Test fun fileNameRoundTripsEvenWithUnderscoresInPackage() {
        val e = Entry(1795000000000, "com.some_app.x", "porn", 82)
        assertEquals(e, ReviewPolicy.parse(ReviewPolicy.fileName(e)))
    }

    @Test fun parseRejectsStrangers() {
        assertNull(ReviewPolicy.parse("notes.txt"))
        assertNull(ReviewPolicy.parse("a~b.jpg"))
        assertNull(ReviewPolicy.parse("x~pkg~porn~82.jpg"))
    }

    @Test fun pruneRemovesExpiredJunkAndOverflowOldestFirst() {
        val now = 10_000_000L
        fun n(t: Long) = ReviewPolicy.fileName(Entry(t, "p", "porn", 60))
        val old = n(now - 1000 - 5000)
        val a = n(now - 100); val b = n(now - 200); val c = n(now - 300)
        val out = ReviewPolicy.toPrune(listOf(old, a, b, c, "junk.tmp"), now, maxAgeMs = 5000, maxCount = 2)
        assertEquals(setOf(old, c, "junk.tmp"), out.toSet())
    }

    @Test fun scoresRoundTripAndBreakdownSortsHighestFirst() {
        val e = Entry(5, "p", "porn", 82, "neutral=0.075,porn=0.824,drawings=0.054")
        assertEquals(e, ReviewPolicy.parse(ReviewPolicy.fileName(e)))
        assertEquals("porn 82% · neutral 8% · drawings 5%", ReviewPolicy.breakdown(e.scores))
        assertEquals("", ReviewPolicy.breakdown(""))
    }
}
