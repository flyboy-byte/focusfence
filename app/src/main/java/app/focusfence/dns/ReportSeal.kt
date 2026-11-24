package app.focusfence.dns

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for the web/email partner's report (D-023): the phone seals each [Digest] with a key that
 * only it and its partners hold, and the server stores the ciphertext. The partner's browser opens it with WebCrypto,
 * so the format is exactly what `crypto.subtle` AES-GCM produces and accepts:
 *
 *     base64url( iv[12] || ciphertext || tag[16] ),  additional data = "ff-report|<deviceId>"
 *
 * The key is 32 random bytes, written as base64url without padding (43 chars). It travels to partners only after the
 * `#` of a link, which browsers never send to a server. Binding the device id as additional data stops a server from
 * passing one phone's report off as another's. Pure JVM, so the format is pinned by tests on both ends.
 */
object ReportSeal {
    private const val IV = 12
    private const val TAG_BITS = 128

    fun newKey(): String = B64.encodeUrl(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun keyBytes(key: String): ByteArray? = runCatching {
        java.util.Base64.getUrlDecoder().decode(key)
    }.getOrNull()?.takeIf { it.size == 32 }

    fun aad(deviceId: String): ByteArray = "ff-report|$deviceId".toByteArray(Charsets.UTF_8)

    fun seal(key: String, plain: ByteArray, deviceId: String): String {
        val k = requireNotNull(keyBytes(key)) { "bad report key" }
        val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad(deviceId))
        return B64.encodeUrl(iv + c.doFinal(plain))
    }

    /** Null when the key is wrong, the blob was altered, or it belongs to another device. */
    fun open(key: String, blob: String, deviceId: String): ByteArray? = runCatching {
        val k = keyBytes(key) ?: return null
        val raw = java.util.Base64.getUrlDecoder().decode(blob)
        if (raw.size < IV + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV)))
        c.updateAAD(aad(deviceId))
        c.doFinal(raw, IV, raw.size - IV)
    }.getOrNull()
}
