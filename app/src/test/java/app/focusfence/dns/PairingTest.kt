package app.focusfence.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private val secret = ByteArray(16) { (it * 17 + 3).toByte() }

    @Test
    fun encodeDecodeRoundTrip() {
        val code = Pairing.encode(secret)
        assertEquals(27 + 6, code.length) // 27 chars in groups of 4 → 6 dashes
        assertArrayEquals(secret, Pairing.decode(code))
    }

    @Test
    fun randomSecretsRoundTrip() {
        repeat(200) {
            val s = Pairing.generateSecret()
            assertArrayEquals(s, Pairing.decode(Pairing.encode(s)))
        }
    }

    @Test
    fun decodeIsLenient() {
        val code = Pairing.encode(secret)
        val messy = " " + code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l') + "\n"
        assertArrayEquals(secret, Pairing.decode(messy))
    }

    @Test
    fun singleTypoIsRejected() {
        val code = Pairing.encode(secret).replace("-", "")
        var caught = 0
        var tried = 0
        for (i in code.indices) {
            for (c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") {
                if (c == code[i]) continue
                tried++
                val bad = code.substring(0, i) + c + code.substring(i + 1)
                if (Pairing.decode(bad) == null) caught++
            }
        }
        // Only a 0<->Z style difference of exactly 31 can slip through.
        assertTrue("caught $caught of $tried", caught >= tried - code.length)
    }

    @Test
    fun adjacentSwapIsRejected() {
        val code = Pairing.encode(secret).replace("-", "")
        for (i in 0 until code.length - 1) {
            if (code[i] == code[i + 1]) continue
            val swapped = code.substring(0, i) + code[i + 1] + code[i] + code.substring(i + 2)
            assertNull("swap at $i", Pairing.decode(swapped))
        }
    }

    @Test
    fun wrongLengthAndCharsRejected() {
        assertNull(Pairing.decode("ABCD"))
        assertNull(Pairing.decode(Pairing.encode(secret) + "X"))
        assertNull(Pairing.decode(Pairing.encode(secret).replaceFirst(Regex("[A-Z0-9]"), "U")))
    }

    @Test
    fun derivedKeysAreStableAndIndependent() {
        val a = Pairing.derive(secret)
        val b = Pairing.derive(secret.copyOf())
        assertEquals(a.mailboxId, b.mailboxId)
        assertEquals(a.token, b.token)
        assertArrayEquals(a.enc, b.enc)
        assertEquals(64, a.mailboxId.length)
        assertFalse(a.enc.contentEquals(a.otp))
        val other = Pairing.derive(Pairing.generateSecret())
        assertNotEquals(a.mailboxId, other.mailboxId)
    }

    @Test
    fun tokenHashHidesToken() {
        val k = Pairing.derive(secret)
        val h = Pairing.tokenHash(k.token)
        assertNotEquals(k.token, h)
        assertEquals(h, Pairing.tokenHash(k.token))
    }

    @Test
    fun sealOpenRoundTripAndTamper() {
        val k = Pairing.derive(secret)
        val aad = k.mailboxId.toByteArray()
        val blob = Pairing.seal(k.enc, "hello".toByteArray(), aad)
        assertEquals("hello", String(Pairing.open(k.enc, blob, aad)!!))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(Pairing.open(k.enc, blob, aad))
    }

    @Test
    fun openFailsForOtherMailboxOrKey() {
        val k = Pairing.derive(secret)
        val blob = Pairing.seal(k.enc, "x".toByteArray(), "box-a".toByteArray())
        assertNull(Pairing.open(k.enc, blob, "box-b".toByteArray()))
        assertNull(Pairing.open(Pairing.derive(Pairing.generateSecret()).enc, blob, "box-a".toByteArray()))
        assertNull(Pairing.open(k.enc, ByteArray(5), "box-a".toByteArray()))
    }

    @Test
    fun codesVerifyWithinSkewWindowOnly() {
        val otp = Pairing.derive(secret).otp
        val t = 1_800_000_000_000L
        val code = Pairing.code(otp, t)
        assertEquals(Pairing.CODE_DIGITS, code.length)
        assertTrue(Pairing.verifyCode(otp, code, t))
        assertTrue(Pairing.verifyCode(otp, code, t + Pairing.CODE_STEP_MS))
        assertTrue(Pairing.verifyCode(otp, code, t - Pairing.CODE_STEP_MS))
        assertFalse(Pairing.verifyCode(otp, code, t + 3 * Pairing.CODE_STEP_MS))
        assertFalse(Pairing.verifyCode(Pairing.derive(Pairing.generateSecret()).otp, code, t))
        assertFalse(Pairing.verifyCode(otp, "123", t))
    }

    @Test
    fun codeChangesEachStep() {
        val otp = Pairing.derive(secret).otp
        val t = 1_800_000_000_000L
        val codes = (0 until 20).map { Pairing.code(otp, t + it * Pairing.CODE_STEP_MS) }.toSet()
        assertTrue(codes.size >= 19)
        assertTrue(Pairing.codeRemainingMs(t) in 1..Pairing.CODE_STEP_MS)
    }
}
