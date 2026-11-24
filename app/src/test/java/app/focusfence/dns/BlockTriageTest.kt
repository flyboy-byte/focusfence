package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockTriageTest {

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
    }

    @Test
    fun pagePartsAndBackgroundHostsAreNotFrontDoors() {
        assertNull(BlockTriage.frontDoorSite("cdn.example.com"))
        assertNull(BlockTriage.frontDoorSite("api.tracker.example.com"))
        assertNull(BlockTriage.frontDoorSite("www.cdn.example.com"))
        assertNull(BlockTriage.frontDoorSite("localhost"))
        assertNull(BlockTriage.frontDoorSite("a..com"))
    }

    @Test
    fun threeTriesInTenMinutesTellPartnersOnce() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        assertNull(e.hit("adult", 0))
        assertNull(e.hit("adult", 2 * min))
        assertEquals(3, e.hit("adult", 4 * min))
        assertNull("quiet for an hour after telling", e.hit("adult", 5 * min))
        assertNull(e.hit("adult", 6 * min))
    }

    @Test
    fun spreadOutTriesDoNotEscalate() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        assertNull(e.hit("adult", 0))
        assertNull(e.hit("adult", 8 * min))
        assertNull("the first one aged out", e.hit("adult", 11 * min))
    }

    @Test
    fun categoriesCountSeparately() {
        val e = BlockTriage.Escalation()
        assertNull(e.hit("adult", 0))
        assertNull(e.hit("gambling", 1))
        assertNull(e.hit("adult", 2))
        assertNull(e.hit("gambling", 3))
        assertEquals(3, e.hit("adult", 4))
    }

    @Test
    fun canTellAgainAfterTheQuietHour() {
        val e = BlockTriage.Escalation()
        val min = 60_000L
        e.hit("adult", 0)
        e.hit("adult", 1 * min)
        assertEquals(3, e.hit("adult", 2 * min))
        assertNull(e.hit("adult", 70 * min))
        assertNull(e.hit("adult", 71 * min))
        assertEquals(3, e.hit("adult", 72 * min))
    }
}
