package app.focusfence.data

import android.content.Context
import app.focusfence.dns.Pin

/**
 * Strict-mode unlock PINs (StrongHold-style), stored only as PBKDF2 hashes —
 * never the PIN itself. Two independent slots:
 *
 * - **owner**: a PIN the owner sets, as an alternative to the timed delay.
 * - **partner**: a PIN the owner does not keep. Generated once, shown to be
 *   handed to the accountability partner (or, with the backend, delivered to
 *   them), so unlocking needs the partner. A `partnerSetAt` timestamp records
 *   when it was established; clearing it is a loosening action.
 *
 * Which authority is required is [UnlockMode]. This is a convenience over the
 * timed delay, not a replacement for it: the delay still governs when no PIN is
 * configured, so a forgotten PIN never permanently locks the user out.
 */
object PinStore {

    enum class UnlockMode { TIMER, OWNER_PIN, PARTNER_PIN }

    private const val KEY_MODE = "unlock_mode"
    private const val KEY_OWNER = "unlock_owner_pin"
    private const val KEY_PARTNER = "unlock_partner_pin"

    fun mode(context: Context): UnlockMode =
        runCatching { UnlockMode.valueOf(prefs(context).getString(KEY_MODE, null) ?: "") }
            .getOrDefault(UnlockMode.TIMER)

    fun setMode(context: Context, mode: UnlockMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun setOwnerPin(context: Context, pin: String) = store(context, KEY_OWNER, Pin.hash(pin))

    fun verifyOwner(context: Context, pin: String): Boolean = verify(context, KEY_OWNER, pin)

    /** Sets a random partner PIN and returns it once, for the owner to pass on. */
    fun generatePartnerPin(context: Context): String {
        val pin = Pin.generate()
        store(context, KEY_PARTNER, Pin.hash(pin))
        return pin
    }

    /**
     * The handed-over PIN, or the current unlock code shown in a paired
     * partner's app (see [app.focusfence.dns.Pairing.code]) — verified offline,
     * so a partner PIN works without a PIN ever being handed over.
     */
    fun verifyPartner(context: Context, pin: String): Boolean =
        verify(context, KEY_PARTNER, pin) ||
            app.focusfence.sync.SyncStore.verifyPartnerCode(context, pin, System.currentTimeMillis())

    fun hasPartnerPin(context: Context): Boolean =
        prefs(context).contains(KEY_PARTNER) || app.focusfence.sync.SyncStore.ownPairings(context).isNotEmpty()

    fun hasOwnerPin(context: Context): Boolean = prefs(context).contains(KEY_OWNER)

    private fun store(context: Context, key: String, h: Pin.Hashed) {
        prefs(context).edit().putString(key, "${h.saltB64}:${h.hashB64}:${h.iterations}").apply()
    }

    private fun verify(context: Context, key: String, pin: String): Boolean {
        val raw = prefs(context).getString(key, null) ?: return false
        val parts = raw.split(":")
        if (parts.size != 3) return false
        return Pin.verify(pin, Pin.Hashed(parts[0], parts[1], parts[2].toIntOrNull() ?: return false))
    }

    private fun prefs(context: Context) = BlocklistStore.prefs(context)
}
