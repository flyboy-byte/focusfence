package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrityWordsTest {
    private fun integ(key: String, was: String, value: String) = mapOf("type" to LogEvents.TYPE_INTEGRITY, "key" to key, "was" to was, "value" to value)

    @Test
    fun partnersReadLooseningInPlainWords() {
        assertEquals("Adult sites list: Block → Report", Digest.alertText(integ("list_adult", "block", "report")))
        assertEquals("Screen-check AI: on → off", Digest.alertText(integ("capture_ai", "true", "false")))
        assertEquals("Screen checks: on → off", Digest.alertText(integ("capture_screenshots", "true", "false")))
        // Keys without a plain name keep the old form.
        assertEquals("private_dns changed: off → hostname", Digest.alertText(integ("private_dns", "off", "hostname")))
    }
}
