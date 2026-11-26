package app.focusfence.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class EciesTest {
    private fun partnerKeys() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun roundTripsThroughPartnerKey() {
        val kp = partnerKeys()
        val msg = "screenshot-bytes-\u0000ÿ".toByteArray()
        val blob = Ecies.encrypt(msg, kp.public)
        assertArrayEquals(msg, Ecies.decrypt(blob, kp.private))
    }

    @Test fun wrongKeyFails() {
        val blob = Ecies.encrypt("x".toByteArray(), partnerKeys().public)
        try {
            Ecies.decrypt(blob, partnerKeys().private)
            fail("should not decrypt with a different key")
        } catch (e: Exception) { /* GCM tag mismatch */ }
    }

    @Test fun hkdfMatchesKnownVector() {
        // RFC 5869 Appendix A.1 (first 16 bytes of the 42-byte OKM).
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12)
        val info = byteArrayOf(0xf0.toByte(),0xf1.toByte(),0xf2.toByte(),0xf3.toByte(),0xf4.toByte(),
            0xf5.toByte(),0xf6.toByte(),0xf7.toByte(),0xf8.toByte(),0xf9.toByte())
        val okm = Ecies.hkdf(ikm, salt, info, 42)
        val hex = okm.joinToString("") { "%02x".format(it) }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex)
    }

    /** Plan 13: the report key sealed to a partner device has its own label, so it can't be passed off as a shot. */
    @Test fun aSealedReportKeyOpensOnlyAsAKey() {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = ReportSeal.newKey()
        val sealed = Ecies.encrypt(key.toByteArray(), kp.public, Ecies.INFO_KEY)
        org.junit.Assert.assertEquals(key, String(Ecies.decrypt(sealed, kp.private, Ecies.INFO_KEY)))
        org.junit.Assert.assertTrue(runCatching { Ecies.decrypt(sealed, kp.private) }.isFailure)
    }

    @Test fun aKeyIdNamesAKeyWithoutGivingItAway() {
        val a = ReportSeal.newKey()
        org.junit.Assert.assertEquals(ReportSeal.keyId(a), ReportSeal.keyId(a))
        org.junit.Assert.assertNotEquals(ReportSeal.keyId(a), ReportSeal.keyId(ReportSeal.newKey()))
        org.junit.Assert.assertEquals(16, ReportSeal.keyId(a).length)
        org.junit.Assert.assertFalse(a.contains(ReportSeal.keyId(a)))
    }
}
