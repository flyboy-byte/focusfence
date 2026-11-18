package app.focusfence.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.focusfence.dns.B64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's identity to the accountability server: an ECDSA P-256 key in
 * Android's keystore (hardware-backed where available). The private key never
 * leaves the keystore, so uploaded records can't be forged from a copy of the
 * app's data. Clearing app data or uninstalling destroys it — which the server
 * then sees as the device going silent, exactly as it should.
 */
object DeviceKey {

    private const val ALIAS = "focusfence_device_v1"

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun publicSpkiB64(): String {
        val ks = keystore()
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }.generateKeyPair()
        }
        return B64.encode(ks.getCertificate(ALIAS).publicKey.encoded)
    }

    /** DER ECDSA signature over [message], base64 — what the server verifies. */
    fun sign(message: ByteArray): String {
        val entry = keystore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return B64.encode(Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(message)
            sign()
        })
    }

    fun delete() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }
}
