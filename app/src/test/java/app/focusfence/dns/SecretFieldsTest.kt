package app.focusfence.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretFieldsTest {

    @Test
    fun passwordFieldsAreSkipped() {
        assertTrue(SecretFields.skip(true, true, null, null, null, null))
        // A web form after "show password": no isPassword, but labelled.
        assertTrue(SecretFields.skip(false, true, null, null, null, "Password"))
        assertTrue(SecretFields.skip(false, true, "Enter your passcode", null, null, null))
        assertTrue(SecretFields.skip(false, true, null, null, "com.example:id/pin_entry", "Enter PIN"))
        assertTrue(SecretFields.skip(false, true, null, "CVV", null, null))
        assertTrue(SecretFields.skip(false, true, null, null, null, "Verification code"))
    }

    @Test
    fun ordinaryFieldsAndTextStay() {
        assertFalse(SecretFields.skip(false, true, "Search or type URL", null, null, "Home"))
        assertFalse(SecretFields.skip(false, true, null, null, null, "Email or username"))
        // Not editable: the label itself, and a "Forgot password?" link, are just text.
        assertFalse(SecretFields.skip(false, false, null, null, null, "Password"))
        assertFalse(SecretFields.namesSecret("bypass the filter"))
        assertFalse(SecretFields.namesSecret("Pinterest"))
    }
}
