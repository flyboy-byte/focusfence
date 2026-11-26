package app.focusfence.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.focusfence.dns.B64
import app.focusfence.dns.Ecies
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plan 13: this phone as one of a partner's devices. Each report it sees gets its own P-256 key pair (so two owners on
 * one server can't be linked by a shared key); the owner's phone seals the report key to the public half.
 *
 * The private half is an ordinary software key, because Android's keystore does ECDH only from Android 12 and the app
 * runs from 8. It is stored wrapped: AES-GCM under a keystore key that never leaves the keystore, so a copy of the
 * app's files alone can't open the report.
 */
object PartnerKey {
    private const val WRAP_ALIAS = "focusfence_partner_wrap_v1"

    class New(val publicSpkiB64: String, val wrapped: String)

    fun create(): New {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return New(B64.encode(kp.public.encoded), wrap(kp.private.encoded))
    }

    /** The report key the owner's phone sealed to this device (the server's base64url `sealed`). Throws if it isn't ours. */
    fun open(wrapped: String, sealed: String): String {
        val priv: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unwrap(wrapped)))
        return String(Ecies.decrypt(java.util.Base64.getUrlDecoder().decode(sealed), priv, Ecies.INFO_KEY))
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return B64.encode(c.iv + c.doFinal(plain))
    }

    private fun unwrap(wrapped: String): ByteArray {
        val raw = B64.decode(wrapped)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        return c.doFinal(raw, 12, raw.size - 12)
    }
}
