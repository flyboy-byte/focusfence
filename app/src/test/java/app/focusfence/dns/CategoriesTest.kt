package app.focusfence.dns

import app.focusfence.dns.Categories.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    private val lists = mapOf(
        "adult" to setOf("badsite.example", "porn.example"),
        "gambling" to setOf("bet.example"),
    )
    private val noRule: (BlockRule) -> Boolean = { false }

    @Test
    fun matchReturnsSortedCategoriesIncludingSubdomains() {
        assertEquals(listOf("adult"), Categories.match("cdn.badsite.example", lists))
        assertEquals(emptyList<String>(), Categories.match("fine.example", lists))
    }

    @Test
    fun reportPolicyLabelsButDoesNotBlock() {
        val d = Categories.decide("porn.example", null, noRule, emptySet(), lists,
            mapOf("adult" to Policy.REPORT), emptySet(), emptySet())
        assertFalse(d.blocks)
        assertEquals(listOf("adult"), d.categories)
        assertEquals(LogEvents.REASON_CATEGORY, d.reason)
    }

    @Test
    fun blockPolicyBlocks() {
        val d = Categories.decide("porn.example", null, noRule, emptySet(), lists,
            mapOf("adult" to Policy.BLOCK), emptySet(), emptySet())
        assertTrue(d.blocks)
        assertEquals(LogEvents.REASON_CATEGORY, d.reason)
    }

    @Test
    fun offPolicyDropsTheLabelEntirely() {
        val d = Categories.decide("porn.example", null, noRule, emptySet(), lists,
            mapOf("adult" to Policy.OFF), emptySet(), emptySet())
        assertFalse(d.blocks)
        assertEquals(emptyList<String>(), d.categories)
        assertEquals(null, d.reason)
    }

    @Test
    fun userRuleBlockBeatsEverythingAndStillRecordsCategories() {
        val rule = BlockRule("porn.example")
        val d = Categories.decide("porn.example", rule, { true }, setOf("porn.example"), lists,
            mapOf("adult" to Policy.BLOCK), emptySet(), emptySet())
        assertTrue(d.blocks)
        assertEquals(LogEvents.REASON_RULE, d.reason)
        assertEquals(listOf("adult"), d.categories) // label kept for the report
    }

    @Test
    fun allowlistOverridesCategoryAndAdsButNotGuards() {
        val allowed = Categories.decide("porn.example", null, noRule, setOf("porn.example"), lists,
            mapOf("adult" to Policy.BLOCK), setOf("porn.example"), emptySet())
        assertFalse(allowed.blocks)
        assertEquals(LogEvents.REASON_ALLOW, allowed.reason)

        val guarded = Categories.decide("doh.example", null, noRule, setOf("doh.example"),
            emptyMap(), emptyMap(), emptySet(), setOf("doh.example"))
        assertTrue(guarded.blocks)
        assertEquals(LogEvents.REASON_GUARD, guarded.reason)
    }

    @Test
    fun guardBeatsAllowlistButRuleStillWinsOverGuard() {
        // A user rule is the top authority even over guards.
        val d = Categories.decide("doh.example", BlockRule("doh.example"), { true },
            emptySet(), emptyMap(), emptyMap(), emptySet(), setOf("doh.example"))
        assertEquals(LogEvents.REASON_RULE, d.reason)
    }

    @Test
    fun adsBlockWhenNoHigherLayerMatches() {
        val d = Categories.decide("ads.example", null, noRule, emptySet(), emptyMap(),
            emptyMap(), setOf("ads.example"), emptySet())
        assertTrue(d.blocks)
        assertEquals(LogEvents.REASON_ADS, d.reason)
    }
}
