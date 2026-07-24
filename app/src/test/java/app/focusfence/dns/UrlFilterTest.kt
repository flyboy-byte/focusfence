package app.focusfence.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFilterTest {

    private val rules = mapOf(
        "facebook.com" to BlockRule("facebook.com", allowPaths = listOf("/marketplace")),
        "reddit.com" to BlockRule("reddit.com"), // plain rule: DNS enforces, not URL filter
    )

    @Test
    fun blocksSiteOutsideAllowedSections() {
        assertTrue(UrlFilter.shouldBlock("https://www.facebook.com/", rules))
        assertTrue(UrlFilter.shouldBlock("facebook.com", rules))
        assertTrue(UrlFilter.shouldBlock("https://facebook.com/watch", rules))
        assertTrue(UrlFilter.shouldBlock("m.facebook.com/groups/123", rules))
        // Prefix must match a whole path segment: /marketplace-fake is not /marketplace.
        assertTrue(UrlFilter.shouldBlock("facebook.com/marketplace-fake", rules))
    }

    @Test
    fun allowsPagesUnderAllowedSections() {
        assertFalse(UrlFilter.shouldBlock("https://www.facebook.com/marketplace", rules))
        assertFalse(UrlFilter.shouldBlock("facebook.com/marketplace/item/123?ref=x", rules))
        assertFalse(UrlFilter.shouldBlock("m.facebook.com/marketplace", rules))
        assertFalse(UrlFilter.shouldBlock("facebook.com/marketplace?query=bike", rules))
    }

    @Test
    fun ignoresUnrelatedSitesAndNonSectionRules() {
        assertFalse(UrlFilter.shouldBlock("https://en.wikipedia.org/wiki/Focus", rules))
        // reddit.com has a plain rule — DNS handles it, URL filter stays out.
        assertFalse(UrlFilter.shouldBlock("https://reddit.com/r/all", rules))
        // Address bar showing a search phrase, not a URL.
        assertFalse(UrlFilter.shouldBlock("how to focus better", rules))
        assertFalse(UrlFilter.shouldBlock("", rules))
    }

    @Test
    fun normalizePathCleansInput() {
        org.junit.Assert.assertEquals("/marketplace", Blocklist.normalizePath("marketplace"))
        org.junit.Assert.assertEquals("/marketplace", Blocklist.normalizePath("/marketplace/"))
        org.junit.Assert.assertEquals("/marketplace", Blocklist.normalizePath("facebook.com/marketplace"))
        org.junit.Assert.assertEquals("/marketplace", Blocklist.normalizePath("https://www.facebook.com/marketplace"))
        assertNull(Blocklist.normalizePath("/"))
        assertNull(Blocklist.normalizePath(""))
    }

    @Test
    fun pathRuleEncodeDecodeRoundTrips() {
        val rule = BlockRule("facebook.com", allowPaths = listOf("/marketplace", "/events"))
        org.junit.Assert.assertEquals("facebook.com|p|/marketplace,/events", rule.encode())
        org.junit.Assert.assertEquals(rule, BlockRule.decode(rule.encode()))
        assertNull(BlockRule.decode("facebook.com|p|"))
    }
}
