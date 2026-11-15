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

    @Test fun contentBundlesDefaultOff() {
        for (id in listOf("adult", "gambling", "social", "piracy", "scam")) {
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
}
