package app.focusfence.dns

import app.focusfence.dns.Classification.RetentionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationTest {

    @Test fun picksTopRiskLabelAndFlagsOverThreshold() {
        val v = Classification.riskVerdict(mapOf("neutral" to 0.1, "porn" to 0.82, "sexy" to 0.4))!!
        assertEquals("porn", v.label)
        assertEquals(0.82, v.score, 1e-9)
        assertTrue(v.flagged)
    }

    @Test fun belowThresholdNotFlagged() {
        val v = Classification.riskVerdict(mapOf("sexy" to 0.5))!!
        assertFalse(v.flagged)
    }

    @Test fun noRiskLabelsReturnsNull() {
        assertNull(Classification.riskVerdict(mapOf("neutral" to 0.99, "drawing" to 0.5)))
    }

    @Test fun retentionModes() {
        assertFalse(Classification.retainImage(RetentionMode.SCORE_ONLY, true))
        assertTrue(Classification.retainImage(RetentionMode.FLAGGED_ONLY, true))
        assertFalse(Classification.retainImage(RetentionMode.FLAGGED_ONLY, false))
        assertTrue(Classification.retainImage(RetentionMode.ALL, false))
    }
}
