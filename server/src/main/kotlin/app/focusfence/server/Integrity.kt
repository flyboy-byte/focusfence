package app.focusfence.server

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Server-side integrity checks — the part that makes the accountability record
 * trustworthy. The phone can stop sending, but it cannot make the server forget
 * that it used to send, nor silently rewrite history that was already uploaded.
 *
 * Three independent checks:
 *  - **sequence continuity**: records carry the phone's own activity-log `seq`;
 *    a gap means lines never arrived or were deleted (shown, not hidden).
 *  - **hash chain**: each batch carries the previous batch's hash; a rewrite or
 *    reorder of already-uploaded data breaks the chain.
 *  - **signature**: ECDSA P-256 / SHA-256 by the device key registered at
 *    enrollment. P-256 rather than Ed25519 because Android's hardware-backed
 *    keystore supports it back to our minSdk 26; Ed25519 needs API 33.
 */
object Integrity {

    fun publicKeyFromBase64Spki(b64: String): PublicKey {
        val der = Base64.getDecoder().decode(b64)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        require(key.encoded.size in 88..95) { "expected a P-256 key" }
        return key
    }

    /** DER-encoded ECDSA signature (what Android's keystore produces), base64. */
    fun verifySignature(key: PublicKey, message: ByteArray, signatureB64: String): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(Base64.getDecoder().decode(signatureB64))
        }
    } catch (e: Exception) {
        false
    }

    /** Canonical bytes a device signs: device id, seq range, prev hash, and the raw records. */
    fun batchMessage(deviceId: String, firstSeq: Long, lastSeq: Long, prevHash: String, recordsRaw: String): ByteArray =
        "batch|$deviceId|$firstSeq|$lastSeq|$prevHash|$recordsRaw".toByteArray()

    /** Canonical bytes for a small signed request: `action|deviceId|t|extra`. */
    fun requestMessage(action: String, deviceId: String, t: Long, extra: String = ""): ByteArray =
        "$action|$deviceId|$t|$extra".toByteArray()

    fun hashBatch(prevHash: String, recordsRaw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prevHash.toByteArray())
        md.update(recordsRaw.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
    }

    fun sha256(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    sealed class Result {
        data class Ok(val newHash: String, val gap: Long) : Result()
        data class Reject(val reason: String) : Result()
    }

    /**
     * Validates a batch against the device's last known [expectedNextSeq] and
     * [knownPrevHash]. A sequence gap is *accepted* (data loss is real and must
     * be recorded) but reported, so a partner sees "N records missing" rather
     * than a clean-looking timeline. Signature or chain failure is rejected.
     */
    fun check(
        signatureValid: Boolean,
        firstSeq: Long,
        lastSeq: Long,
        prevHash: String,
        knownPrevHash: String,
        expectedNextSeq: Long,
        recordsRaw: String,
    ): Result {
        if (!signatureValid) return Result.Reject("bad signature")
        if (prevHash != knownPrevHash) return Result.Reject("hash chain broken")
        if (lastSeq < firstSeq) return Result.Reject("inverted seq range")
        if (firstSeq < expectedNextSeq) return Result.Reject("replayed/overlapping seq")
        if (recordsRaw.isBlank()) return Result.Reject("empty batch")
        val gap = firstSeq - expectedNextSeq
        return Result.Ok(hashBatch(prevHash, recordsRaw), gap)
    }
}
