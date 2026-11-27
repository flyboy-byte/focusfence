package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {

    @Test
    fun barsAndDefault() {
        assertEquals(0.45, Sensitivity.STRICT.threshold, 0.0)
        assertEquals(0.50, Sensitivity.STANDARD.threshold, 0.0)
        assertEquals(0.70, Sensitivity.RELAXED.threshold, 0.0)
        assertEquals(Sensitivity.STANDARD, Sensitivity.fromName(null))
        assertEquals(Sensitivity.STANDARD, Sensitivity.fromName("bogus"))
    }

    @Test
    fun onlyLessStrictIsALoosening() {
        assertTrue(Sensitivity.STANDARD.loosensTo(Sensitivity.RELAXED))
        assertFalse(Sensitivity.STANDARD.loosensTo(Sensitivity.STRICT))
        assertTrue(Sensitivity.loosened(Sensitivity.detail(Sensitivity.STRICT, Sensitivity.STANDARD)))
        assertFalse(Sensitivity.loosened(Sensitivity.detail(Sensitivity.RELAXED, Sensitivity.STRICT)))
    }

    @Test
    fun partnersHearAtOnceOnlyWhenLoosened() {
        fun rec(from: Sensitivity, to: Sensitivity) =
            mapOf<String, Any?>("type" to LogEvents.TYPE_SERVICE, "comp" to "monitor", "state" to "sensitivity", "detail" to Sensitivity.detail(from, to))
        val looser = rec(Sensitivity.STANDARD, Sensitivity.RELAXED)
        assertEquals("Screen check sensitivity set to Relaxed (less strict)", Digest.alertText(looser))
        assertTrue(Digest.isUrgent(looser))
        val stricter = rec(Sensitivity.STANDARD, Sensitivity.STRICT)
        assertEquals("Screen check sensitivity set to Strict", Digest.alertText(stricter))
        assertFalse(Digest.isUrgent(stricter))
    }
}
