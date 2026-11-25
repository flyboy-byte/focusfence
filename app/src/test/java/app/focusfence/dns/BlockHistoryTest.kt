package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockHistoryTest {
    private fun dns(t: Long, host: String, reason: String?, rule: String? = null, cat: String? = null) =
        linkedMapOf<String, Any?>("t" to t, "type" to "dns", "host" to host, "decision" to "blocked").apply {
            if (reason != null) put("reason", reason)
            if (rule != null) put("rule", rule)
            if (cat != null) put("cat", cat)
        }

    private val min = 60_000L

    @Test
    fun oneVisitIsOneEntry() {
        val e = BlockHistory.entries(sequenceOf(
            dns(0, "reddit.com", "rule", rule = "reddit.com"),
            dns(1_000, "www.reddit.com", "rule", rule = "reddit.com"),
            dns(2 * min, "reddit.com", "rule", rule = "reddit.com"),
        ))
        assertEquals(listOf(BlockHistory.Entry("reddit.com", 0, 2 * min, 3, "rule")), e)
    }

    @Test
    fun aLaterVisitIsANewEntryNewestFirst() {
        val e = BlockHistory.entries(sequenceOf(
            dns(0, "reddit.com", "rule", rule = "reddit.com"),
            dns(30 * min, "reddit.com", "rule", rule = "reddit.com"),
        ))
        assertEquals(listOf(30 * min, 0L), e.map { it.first })
    }

    @Test
    fun deeperHostsAdsAndQuietListsAreLeftOut() {
        val e = BlockHistory.entries(sequenceOf(
            dns(0, "i.redd.it", "rule", rule = "redd.it"),
            dns(0, "doubleclick.net", "ads"),
            dns(0, "tracker.example", "cat", cat = "security"),
            dns(0, "dns.google", "cat", cat = "bypass"),
            dns(0, "cdn.casino.example", "cat", cat = "gambling"),
            mapOf("t" to 0L, "type" to "dns", "host" to "allowed.example", "decision" to "allowed"),
        ))
        assertEquals(emptyList<BlockHistory.Entry>(), e)
    }

    @Test
    fun aListSiteIsNamedByItsFrontDoorAndCategory() {
        val e = BlockHistory.entries(sequenceOf(dns(0, "www.casino.example", "cat", cat = "security,gambling")))
        assertEquals(listOf(BlockHistory.Entry("casino.example", 0, 0, 1, "gambling")), e)
    }

    @Test
    fun trackersAreEverythingElseByCompany() {
        val t = BlockHistory.trackers(sequenceOf(
            dns(0, "sdk.iad-02.braze.com", "cat", cat = "security"),
            dns(5, "sdk.fra-01.braze.com", "cat", cat = "security"),
            dns(9, "doubleclick.net", "ads"),
            dns(9, "reddit.com", "rule", rule = "reddit.com"),
        ))
        assertEquals(listOf(BlockHistory.Tracker("braze.com", 2, 5), BlockHistory.Tracker("doubleclick.net", 1, 9)), t)
        assertEquals("example.co.uk", BlockTriage.registrable("a.b.example.co.uk"))
    }
}
