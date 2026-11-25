package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundlesTest {

    @Test fun bundleIdsAreUnique() {
        val ids = Categories.BUNDLES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun securityThreatBypassDefaultToBlock() {
        for (id in listOf("security", "threat", "bypass")) {
            assertEquals("$id should default to BLOCK", Categories.Policy.BLOCK, Categories.bundle(id)!!.defaultPolicy)
        }
    }

    @Test fun contentBundlesDefaults() {
        // Accountability default: the two core categories are detected and reported,
        // never blocked unless the owner chooses; the rest stay off.
        for (id in listOf("adult", "gambling")) {
            assertEquals("$id should default to REPORT", Categories.Policy.REPORT, Categories.bundle(id)!!.defaultPolicy)
        }
        for (id in listOf("social", "piracy", "scam")) {
            assertEquals("$id should default to OFF", Categories.Policy.OFF, Categories.bundle(id)!!.defaultPolicy)
        }
    }

    @Test fun securityIsTheOnlyTieredBundleAndHasNoHardcodedUrlBranch() {
        assertTrue(Categories.bundle("security")!!.tiered)
        assertEquals(1, Categories.BUNDLES.count { it.tiered })
    }

    @Test fun everyNonTieredBundleHasAUrlAndEveryTierUrlIsSet() {
        for (b in Categories.BUNDLES) {
            if (!b.tiered) assertTrue("${b.id} needs a url", b.url.isNotBlank())
        }
        for (t in Categories.BaseTier.values()) assertTrue(t.url.startsWith("https://"))
        assertEquals(Categories.BaseTier.PRO, Categories.BaseTier.DEFAULT)
    }

    @Test fun bundleLookupMatchesList() {
        assertNotNull(Categories.bundle("bypass"))
        assertEquals(null, Categories.bundle("nope"))
    }

    @org.junit.Test
    fun adultAndBypassAreCoreAndNeverOff() {
        val core = Categories.BUNDLES.filter { it.core }.map { it.id }.toSet()
        org.junit.Assert.assertEquals(setOf("adult", "bypass"), core)
        org.junit.Assert.assertEquals(Categories.Policy.REPORT, Categories.effectivePolicy(Categories.bundle("adult"), Categories.Policy.OFF))
        org.junit.Assert.assertEquals(Categories.Policy.BLOCK, Categories.effectivePolicy(Categories.bundle("adult"), Categories.Policy.BLOCK))
        org.junit.Assert.assertEquals(Categories.Policy.OFF, Categories.effectivePolicy(Categories.bundle("social"), Categories.Policy.OFF))
    }
}
