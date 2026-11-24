package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockTriageTest {

    private var n = 0
    private fun nextSite() = "site${n++}.example"

    @Test
    fun onlyAdultGamblingAndScamAreHighRisk() {
        assertEquals("adult", BlockTriage.highRisk(listOf("security", "adult")))
        assertEquals("gambling", BlockTriage.highRisk(listOf("gambling")))
        assertEquals("scam", BlockTriage.highRisk(listOf("scam", "threat")))
        assertNull(BlockTriage.highRisk(listOf("security", "threat", "social")))
        assertEquals("adult picks first", "adult", BlockTriage.highRisk(listOf("gambling", "adult")))
    }

    @Test
    fun frontDoorIsTheBareSiteOrItsWwwAndMobileNames() {
        assertEquals("example.com", BlockTriage.frontDoorSite("example.com"))
        assertEquals("example.com", BlockTriage.frontDoorSite("www.example.com"))
        assertEquals("example.com", BlockTriage.frontDoorSite("m.example.com."))
        assertEquals("example.co.uk", BlockTriage.frontDoorSite("www.example.co.uk"))
        assertEquals("a site named mobile.de keeps its name", "mobile.de", BlockTriage.frontDoorSite("mobile.de"))
        assertEquals("m.tv", BlockTriage.frontDoorSite("m.tv"))
    }

    @Test
    fun pagePartsAndBackgroundHostsAreNotFrontDoors() {
        assertNull(BlockTriage.frontDoorSite("cdn.example.com"))
        assertNull(BlockTriage.frontDoorSite("api.tracker.example.com"))
        assertNull(BlockTriage.frontDoorSite("www.cdn.example.com"))
        assertNull(BlockTriage.frontDoorSite("localhost"))
        assertNull(BlockTriage.frontDoorSite("a..com"))
        assertNull("cdn of bet.io, not a front door", BlockTriage.frontDoorSite("cdn.bet.io"))
        assertNull(BlockTriage.frontDoorSite("img.xxx.tv"))
    }

    @Test
    fun threeTriesInTenMinutesTellPartnersOnce() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        assertNull(e.hit("adult", nextSite(), 0))
        assertNull(e.hit("adult", nextSite(), 2 * min))
        assertEquals(3, e.hit("adult", nextSite(), 4 * min))
        assertNull("quiet for an hour after telling", e.hit("adult", nextSite(), 5 * min))
        assertNull(e.hit("adult", nextSite(), 6 * min))
    }

    @Test
    fun spreadOutTriesDoNotEscalate() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        assertNull(e.hit("adult", nextSite(), 0))
        assertNull(e.hit("adult", nextSite(), 8 * min))
        assertNull("the first one aged out", e.hit("adult", nextSite(), 11 * min))
    }

    @Test
    fun categoriesCountSeparately() {
        val e = BlockTriage.Escalation()
        assertNull(e.hit("adult", nextSite(), 0))
        assertNull(e.hit("gambling", nextSite(), 1))
        assertNull(e.hit("adult", nextSite(), 2))
        assertNull(e.hit("gambling", nextSite(), 3))
        assertEquals(3, e.hit("adult", nextSite(), 4))
    }

    @Test
    fun canTellAgainAfterTheQuietHour() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        e.hit("adult", nextSite(), 0)
        e.hit("adult", nextSite(), 1 * min)
        assertEquals(3, e.hit("adult", nextSite(), 2 * min))
        assertNull(e.hit("adult", nextSite(), 70 * min))
        assertNull(e.hit("adult", nextSite(), 71 * min))
        assertEquals(3, e.hit("adult", nextSite(), 72 * min))
    }

    @Test
    fun aTabLeftOpenIsOneTryNotThree() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        assertNull(e.hit("adult", "x.example", 0))
        assertNull("re-resolved after the TTL", e.hit("adult", "x.example", 2 * min))
        assertNull(e.hit("adult", "x.example", 4 * min))
        assertNull("a different site is a new try", e.hit("adult", "y.example", 6 * min))
        assertEquals("third separate try: x again after 5 min, then y and x", 3, e.hit("adult", "x.example", 7 * min))
    }
}
