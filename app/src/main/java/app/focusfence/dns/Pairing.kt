package app.focusfence.dns

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App-to-app pairing by a shared key. One phone generates a random 128-bit
 * secret and shows it as a short code ("pairing key"); the user sends it to
 * their partner over any message app, and the partner types it into their copy
 * of the app. Everything else is derived from that secret, so **the server
 * never sees it**:
 *
 *  - [Keys.mailboxId]: where the two apps meet on the server (a blind mailbox).
 *  - [Keys.token]: proves the right to read/write that mailbox. The server
 *    stores only its SHA-256 ([tokenHash]), so a server breach can't write.
 *  - [Keys.enc]: AES-256-GCM key for every message. The server relays
 *    ciphertext it cannot read.
 *  - [Keys.otp]: seed for short-lived unlock codes ([code]) that the partner's
 *    app shows and this phone verifies offline — the "partner PIN" without a
 *    PIN ever being handed over.
 *
 * The four are independent HKDF outputs, so knowing one (e.g. the token on the
 * server) reveals nothing about the others. Pure JVM.
 */
object Pairing {

    const val SECRET_BYTES = 16

    /** Crockford base32: no I, L, O, U, so the code survives being read aloud or retyped. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Codes rotate every 10 minutes; one step either side is accepted for clock skew. */
    const val CODE_STEP_MS = 10 * 60_000L
    const val CODE_DIGITS = 8

    class Keys(val mailboxId: String, val token: String, val enc: ByteArray, val otp: ByteArray)

    fun generateSecret(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    /**
     * 16 bytes → 26 base32 chars + 1 check char, grouped as XXXX-XXXX-…-XXX.
     * The check char catches a mistyped character or two swapped neighbours, so a
     * typo fails at entry instead of pairing into an empty mailbox.
     */
    fun encode(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES)
        val chars = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in secret) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                chars.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) chars.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        chars.append(checkChar(chars))
        return chars.chunked(4).joinToString("-")
    }

    /** Lenient: any case, spaces/dashes ignored, O→0 and I/L→1. Null if malformed or the check fails. */
    fun decode(text: String): ByteArray? {
        val clean = StringBuilder()
        for (c in text.uppercase()) {
            when (c) {
                ' ', '-', '\n', '\t' -> Unit
                'O' -> clean.append('0')
                'I', 'L' -> clean.append('1')
                else -> if (c in ALPHABET) clean.append(c) else return null
            }
        }
        if (clean.length != 27) return null
        val body = clean.substring(0, 26)
        if (checkChar(body) != clean[26]) return null
        val out = ByteArray(SECRET_BYTES)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in body) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                if (pos < SECRET_BYTES) out[pos++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
            }
        }
        return if (pos == SECRET_BYTES) out else null
    }

    /**
     * Position-weighted sum mod 31 (prime): a single substitution or an adjacent
     * swap changes it unless the characters differ by exactly 31 (0 vs Z).
     */
    private fun checkChar(body: CharSequence): Char {
        var sum = 0
        for ((i, c) in body.withIndex()) sum += (i + 1) * ALPHABET.indexOf(c)
        return ALPHABET[sum % 31]
    }

    fun derive(secret: ByteArray): Keys {
        fun h(label: String, len: Int) = Ecies.hkdf(secret, SALT, "focusfence pairing v1 $label".toByteArray(), len)
        return Keys(
            mailboxId = hex(h("mailbox", 32)),
            token = B64.encodeUrl(h("token", 32)),
            enc = h("enc", 32),
            otp = h("otp", 32),
        )
    }

    /** What the server stores for a mailbox token. Shared with `server/`. */
    fun tokenHash(token: String): String =
        B64.encodeUrl(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /** AES-256-GCM: random 12-byte nonce ‖ ciphertext+tag. [aad] binds the message to its mailbox. */
    fun seal(enc: ByteArray, plain: ByteArray, aad: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** Null if the blob was tampered with, or sealed under another key or mailbox. */
    fun open(enc: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? = try {
        if (blob.size < 12 + 16) {
            null
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad)
            c.doFinal(blob, 12, blob.size - 12)
        }
    } catch (e: Exception) {
        null
    }

    /** RFC 4226/6238-style code for the step containing [nowMs]. */
    fun code(otp: ByteArray, nowMs: Long): String = codeForStep(otp, nowMs / CODE_STEP_MS)

    /** Accepts the current step and one either side (clock skew between the two phones). */
    fun verifyCode(otp: ByteArray, code: String, nowMs: Long): Boolean {
        val c = code.filter { it.isDigit() }
        if (c.length != CODE_DIGITS) return false
        val step = nowMs / CODE_STEP_MS
        return (-1L..1L).any { MessageDigest.isEqual(codeForStep(otp, step + it).toByteArray(), c.toByteArray()) }
    }

    /** Milliseconds until the code shown now changes. */
    fun codeRemainingMs(nowMs: Long): Long = CODE_STEP_MS - nowMs % CODE_STEP_MS

    private fun codeForStep(otp: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(otp, "HmacSHA256"))
        val msg = ByteArray(8)
        for (i in 0 until 8) msg[7 - i] = (step ushr (8 * i)).toByte()
        val h = mac.doFinal(msg)
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or (h[off + 3].toInt() and 0xff)
        var mod = 1
        repeat(CODE_DIGITS) { mod *= 10 }
        return (bin % mod).toString().padStart(CODE_DIGITS, '0')
    }

    private val SALT = "focusfence-pairing".toByteArray()

    private fun hex(b: ByteArray): String = buildString { for (x in b) append("%02x".format(x)) }
}
