package app.focusfence.dns

import org.junit.Assert.assertEquals
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
}
