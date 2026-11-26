package app.focusfence.dns

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts retained screenshots to the accountability partner's public key so
 * the server only ever stores ciphertext. Construction (chosen because both
 * Android's JCA and browsers' WebCrypto implement every step natively, so the
 * partner decrypts in the dashboard with no library):
 *
 *   ephemeral P-256 key → ECDH with partner key → HKDF-SHA256(salt=empty,
 *   info="focusfence-shot-v1") → AES-256-GCM, 12-byte random IV.
 *
 * Wire format: "FFS1" | u16 len | ephemeral pubkey (SPKI DER) | 12-byte IV | ciphertext+tag.
 * Pure JVM; unit-tested round trip.
 */
object Ecies {

    private val MAGIC = byteArrayOf('F'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    private val INFO = "focusfence-shot-v1".toByteArray()
    /** Plan 13: the report key sealed to one partner device. Its own label, so a sealed key can't pass as a shot. */
    val INFO_KEY = "focusfence-key-v1".toByteArray()

    fun publicKeyFromSpki(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    fun encrypt(plain: ByteArray, recipient: PublicKey, info: ByteArray = INFO): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val eph = kpg.generateKeyPair()
        val key = deriveKey(eph.private, recipient, info)
        val iv = Pin.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        val ephDer = eph.public.encoded
        return ByteBuffer.allocate(4 + 2 + ephDer.size + 12 + ct.size)
            .put(MAGIC).putShort(ephDer.size.toShort()).put(ephDer).put(iv).put(ct).array()
    }

    /** Recipient-side decrypt: a partner's app opening its sealed report key, and tests (a browser does it in report.js). */
    fun decrypt(blob: ByteArray, recipientPrivate: PrivateKey, info: ByteArray = INFO): ByteArray {
        val bb = ByteBuffer.wrap(blob)
        val magic = ByteArray(4).also { bb.get(it) }
        require(magic.contentEquals(MAGIC)) { "not a Focus Fence sealed blob" }
        val ephDer = ByteArray(bb.short.toInt()).also { bb.get(it) }
        val iv = ByteArray(12).also { bb.get(it) }
        val ct = ByteArray(bb.remaining()).also { bb.get(it) }
        val key = deriveKey(recipientPrivate, publicKeyFromSpki(ephDer), info)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    private fun deriveKey(priv: PrivateKey, pub: PublicKey, info: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return hkdf(ka.generateSecret(), ByteArray(0), info, 32)
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }
}
