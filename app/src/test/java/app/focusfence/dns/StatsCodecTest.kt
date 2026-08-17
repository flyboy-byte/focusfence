package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCodecTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val counts = linkedMapOf("reddit.com" to 42, StatsCodec.KEY_ADS to 310, "x.com" to 7)
        assertEquals(counts, StatsCodec.decode(StatsCodec.encode(counts)))
    }

    @Test
    fun decodeSkipsMalformedParts() {
        assertEquals(
            mutableMapOf("a.com" to 1),
            StatsCodec.decode("a.com=1;garbage;=5;b.com=zero;c.com=0"),
        )
        assertEquals(emptyMap<String, Int>(), StatsCodec.decode(""))
    }

    @Test
    fun totalsRespectDomainFilter() {
        val counts = mapOf("reddit.com" to 10, StatsCodec.KEY_ADS to 90, StatsCodec.KEY_GUARD to 5)
        assertEquals(105, StatsCodec.total(counts))
        assertEquals(10, StatsCodec.total(counts, domainsOnly = true))
    }

    @Test
    fun sourceBucketsRoundTripAndCountAsListBuckets() {
        val bucket = StatsCodec.SRC_PREFIX + "oisd_small"
        val counts = linkedMapOf("reddit.com" to 3, bucket to 41)
        assertEquals(counts, StatsCodec.decode(StatsCodec.encode(counts)))
        assertTrue(StatsCodec.isListBucket(bucket))
        assertTrue(StatsCodec.isListBucket(StatsCodec.KEY_ADS))
        assertTrue(StatsCodec.isListBucket(StatsCodec.KEY_GUARD))
        assertFalse(StatsCodec.isListBucket("reddit.com"))
        assertEquals("oisd_small", StatsCodec.sourceId(bucket))
        assertNull(StatsCodec.sourceId(StatsCodec.KEY_ADS))
        assertNull(StatsCodec.sourceId("reddit.com"))
        // The legacy aggregate and the new buckets are both excluded from the
        // domains-only total.
        assertEquals(3, StatsCodec.total(counts, domainsOnly = true))
    }
}
