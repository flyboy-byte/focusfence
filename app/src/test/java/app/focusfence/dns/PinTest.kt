package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {
    @Test fun verifiesCorrectPinAndRejectsWrong() {
        val h = Pin.hash("2468", iterations = 1000)
        assertTrue(Pin.verify("2468", h))
        assertFalse(Pin.verify("2469", h))
    }

    @Test fun saltMakesHashesUnique() {
        assertNotEquals(Pin.hash("1234", iterations = 1000).hashB64, Pin.hash("1234", iterations = 1000).hashB64)
    }

    @Test fun generatesRequestedLength() {
        assertEquals(10, Pin.generate().length)
        assertTrue(Pin.generate(6).all { it.isDigit() })
    }
}
