package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportTest {
    private val info = BugReport.Info("2.25.0-accountable", 1, "ok (1)", "16 (SDK 36)", "Google Pixel 9")
    private val numbers = BugReport.Numbers(true, "on", true, false, true, true, true, 10.16, 3.03, 58474.0, 8.2, 385, mapOf("sync:http" to 3L), 3)

    @Test
    fun theSummaryIsNumbersOnly() {
        val s = BugReport.summary(numbers)
        assertTrue(s.contains("battery screen on 10.2 %/h, screen off 3.0 %/h"))
        assertTrue(s.contains("error sync:http ×3"))
        assertFalse(s.contains("http://") || s.contains("https://") || s.contains(".com"))
    }

    @Test
    fun theLinkFillsTheIssueForm() {
        val u = BugReport.url("flyboy-byte/focusfence", info, null)
        assertTrue(u.startsWith("https://github.com/flyboy-byte/focusfence/issues/new?template=bug_report.yml&labels=bug&"))
        assertTrue(u.contains("version=2.25.0-accountable+%28protocol+1%2C+server+ok+%281%29%29"))
        assertFalse(u.contains("summary="))
        assertTrue(BugReport.url("x/y", info, BugReport.summary(numbers)).contains("&summary=filter+on"))
        assertEquals(false, u.contains(" "))
    }

    @Test
    fun featureRequestsAndFeedbackUseTheirOwnFormsAndNeverTheSummary() {
        val f = BugReport.url("x/y", info, "filter on", BugReport.Kind.FEATURE)
        assertTrue(f.contains("template=feature_request.yml&labels=enhancement&"))
        assertFalse(f.contains("summary="))
        assertTrue(BugReport.url("x/y", info, null, BugReport.Kind.FEEDBACK).contains("template=feedback.yml&labels=feedback&"))
    }
}
