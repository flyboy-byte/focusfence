package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyCheckTest {
    private val anchors = 5
    private fun output(vararg hits: Triple<String, Int, Float>): FloatArray {
        val out = FloatArray((4 + BodyCheck.LABELS.size) * anchors)
        for ((label, anchor, score) in hits) out[(4 + BodyCheck.LABELS.indexOf(label)) * anchors + anchor] = score
        return out
    }

    @Test
    fun aFaceOrFeetIsNotAConfirmation() {
        // Logan's video-call false alarm: a face at 0.78 and nothing else.
        val best = BodyCheck.best(output(Triple("FACE_FEMALE", 2, 0.78f), Triple("FEET_EXPOSED", 1, 0.9f)), anchors)
        assertEquals(0.78, best.getValue("FACE_FEMALE"), 1e-6)
        assertNull(BodyCheck.confirm(best))
        assertEquals("none found", BodyCheck.summary(best))
    }

    @Test
    fun theStrongestExplicitPartOverTheBarConfirms() {
        val best = BodyCheck.best(output(
            Triple("FEMALE_BREAST_EXPOSED", 0, 0.30f), Triple("FEMALE_BREAST_EXPOSED", 4, 0.82f),
            Triple("BUTTOCKS_EXPOSED", 3, 0.5f),
        ), anchors)
        assertEquals("FEMALE_BREAST_EXPOSED" to 0.82, BodyCheck.confirm(best)?.let { it.first to Math.round(it.second * 100) / 100.0 })
        assertEquals("FEMALE_BREAST_EXPOSED 82%", BodyCheck.summary(best))
    }

    @Test
    fun justUnderTheBarIsNotEnough() {
        assertNull(BodyCheck.confirm(BodyCheck.best(output(Triple("MALE_GENITALIA_EXPOSED", 0, 0.39f)), anchors)))
    }

    @Test
    fun theReviewCardSaysWhatTheBodyCheckFound() {
        val none = BodyCheck.best(output(Triple("FACE_MALE", 0, 0.8f)), anchors)
        assertEquals("porn 83% · neutral 7%\nbody check: nothing exposed", ReviewPolicy.breakdown("porn=0.83,neutral=0.07," + BodyCheck.compact(none)))
        val hit = BodyCheck.best(output(Triple("BUTTOCKS_EXPOSED", 0, 0.66f)), anchors)
        assertEquals("porn 90%\nbody check: buttocks exposed 66%", ReviewPolicy.breakdown("porn=0.9," + BodyCheck.compact(hit)))
        // The compact form must survive being part of a file name.
        val name = ReviewPolicy.fileName(ReviewPolicy.Entry(1, "com.x", "porn", 90, "porn=0.9," + BodyCheck.compact(hit)))
        assertEquals("porn=0.9,body.BUTTOCKS_EXPOSED=0.660", ReviewPolicy.parse(name)?.scores)
    }

    @Test
    fun lingerieIsSuggestiveNudityStillWinsAndFacesAreNeither() {
        val bra = BodyCheck.best(output(Triple("FEMALE_BREAST_COVERED", 1, 0.62f), Triple("FACE_FEMALE", 0, 0.9f)), anchors)
        assertEquals(BodyCheck.Kind.SUGGESTIVE, BodyCheck.classify(bra).kind)
        assertNull(BodyCheck.confirm(bra)) // never a partner flag or an automatic send
        assertEquals("suggestive: FEMALE_BREAST_COVERED 62%", BodyCheck.summary(bra))
        assertEquals("sexy 70%\nbody check: suggestive (female breast covered 62%)", ReviewPolicy.breakdown("sexy=0.7," + BodyCheck.compact(bra)))

        val both = BodyCheck.best(output(Triple("BUTTOCKS_COVERED", 1, 0.9f), Triple("FEMALE_BREAST_EXPOSED", 2, 0.5f)), anchors)
        assertEquals(BodyCheck.Kind.NUDITY, BodyCheck.classify(both).kind)

        // Logan's video-call false alarm: covered-part scores around 0.01.
        val face = BodyCheck.best(output(Triple("FACE_FEMALE", 0, 0.78f), Triple("FEMALE_BREAST_COVERED", 3, 0.01f)), anchors)
        assertEquals(BodyCheck.Kind.NONE, BodyCheck.classify(face).kind)
    }

    @Test
    fun suggestiveGoesInTheReportNotAsAnAlert() {
        val f = LogEvents.service(1, "monitor", "suggestive", "Instagram")
        assertEquals("Suggestive image (lingerie or swimwear) in Instagram", Digest.alertText(f))
        assertNull(Digest.urgentText(f))
    }
}
