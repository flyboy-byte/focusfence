package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSourcesTest {

    @Test
    fun idsAreUniqueAndSafeEverywhereTheyAreUsed() {
        val ids = FilterSources.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            // Ids name prefs keys, on-disk files and stats buckets: the stats
            // codec forbids '=' and ';', the bucket scheme reserves ':', and a
            // file name must stay boring.
            assertTrue("bad id: $id", id.isNotEmpty())
            assertTrue("bad id: $id", id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' })
        }
    }

    @Test
    fun everySourceIsFullyDescribed() {
        for (source in FilterSources.ALL) {
            assertTrue(source.name.isNotBlank())
            assertTrue("plain-http url: ${source.url}", source.url.startsWith("https://"))
            assertTrue(source.license.isNotBlank())
            assertTrue(source.licenseUrl.startsWith("https://"))
            assertTrue(source.homepageUrl.startsWith("https://"))
            assertTrue(
                "unknown category: ${source.category}",
                source.category in FilterSources.CATEGORIES,
            )
        }
    }

    @Test
    fun everyCategoryHasADefaultSource() {
        for (category in FilterSources.CATEGORIES) {
            val default = FilterSources.defaultFor(category)
            assertNotNull("category $category has no sources", default)
            assertEquals(category, default!!.category)
        }
    }

    @Test
    fun legacyIdIsInTheCatalog() {
        // The pre-W11 migration folds the old single list onto this id; if it
        // ever leaves the catalog the migration silently drops the user's list.
        assertNotNull(FilterSources.byId(FilterSources.LEGACY_ID))
    }

    @Test
    fun lookupAndCategoryFilterAgree() {
        for (source in FilterSources.ALL) {
            assertEquals(source, FilterSources.byId(source.id))
            assertTrue(source in FilterSources.inCategory(source.category))
        }
    }
}
