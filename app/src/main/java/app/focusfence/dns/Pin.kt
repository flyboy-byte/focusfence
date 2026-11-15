package app.focusfence.dns

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN unlock for strict mode (StrongHold-style). The PIN itself is never
 * stored: only a salted PBKDF2 hash. A "partner PIN" is the same mechanism with
 * a random PIN the owner never sees after handing it over (shown once, or sent
 * to the partner by the backend). Pure JVM (javax.crypto exists on Android).
 */
object Pin {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    data class Hashed(val saltB64: String, val hashB64: String, val iterations: Int = ITERATIONS)

    fun hash(pin: String, salt: ByteArray = randomBytes(16), iterations: Int = ITERATIONS): Hashed {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val h = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)).encoded
        return Hashed(B64.encode(salt), B64.encode(h), iterations)
    }

    fun verify(pin: String, stored: Hashed): Boolean {
        val again = hash(pin, B64.decode(stored.saltB64), stored.iterations)
        return MessageDigest.isEqual(B64.decode(again.hashB64), B64.decode(stored.hashB64))
    }

    /** A random numeric PIN for a partner to hold; 10 digits resists guessing without rate limits. */
    fun generate(digits: Int = 10): String {
        val r = SecureRandom()
        return buildString { repeat(digits) { append(r.nextInt(10)) } }
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

/** Base64 without android.util or java.util.Base64 API-level concerns (java.util.Base64 is API 26+, our minSdk). */
object B64 {
    fun encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    fun encodeUrl(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
