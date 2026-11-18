package app.focusfence.sync

import android.content.Context
import android.content.SharedPreferences
import app.focusfence.dns.LogJson
import app.focusfence.dns.Pairing

/**
 * Sync state, in its own prefs file: it changes on every upload, and keeping it
 * out of the `focusfence` file means it never wakes the services' rule
 * listeners.
 *
 * Two independent partner channels live here:
 *  - **server**: device id + upload cursor (seq, hash chain) for the web/email
 *    partner path.
 *  - **pairings**: app-to-app. [OwnPairing] = someone watching this phone;
 *    [Watch] = a phone this user is a partner for. Each holds the pairing key,
 *    from which everything else is derived (see [Pairing]).
 */
object SyncStore {

    private const val FILE = "focusfence_sync"
    private const val KEY_SERVER = "server_url"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_SEQ = "uploaded_seq"
    private const val KEY_HASH = "last_hash"
    private const val KEY_HEARTBEAT = "last_heartbeat"
    private const val KEY_OK = "last_ok"
    private const val KEY_ERROR = "last_error"
    private const val OWN = "own:"
    private const val WATCH = "watch:"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER, url.trim().trimEnd('/')).apply()
    }

    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE, null)

    /**
     * Uploads start from *now* ([startSeq]), not the whole local history: the
     * partner sees what happens after they're set up. The server doesn't count
     * the jump to the first batch's seq as a gap.
     */
    fun setEnrolled(context: Context, deviceId: String?, startSeq: Long) {
        prefs(context).edit().putString(KEY_DEVICE, deviceId).putLong(KEY_SEQ, startSeq).putString(KEY_HASH, "GENESIS").commit()
    }

    fun uploadedSeq(context: Context): Long = prefs(context).getLong(KEY_SEQ, 0)
    fun lastHash(context: Context): String = prefs(context).getString(KEY_HASH, "GENESIS") ?: "GENESIS"

    fun setCursor(context: Context, seq: Long, hash: String) {
        prefs(context).edit().putLong(KEY_SEQ, seq).putString(KEY_HASH, hash).commit()
    }

    fun lastHeartbeat(context: Context): Long = prefs(context).getLong(KEY_HEARTBEAT, 0)
    fun lastOk(context: Context): Long = prefs(context).getLong(KEY_OK, 0)
    fun lastError(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    fun recordResult(context: Context, now: Long, error: String?, heartbeat: Boolean) {
        prefs(context).edit().apply {
            if (error == null) {
                putLong(KEY_OK, now)
                remove(KEY_ERROR)
            } else {
                putString(KEY_ERROR, error)
            }
            if (heartbeat) putLong(KEY_HEARTBEAT, now)
        }.apply()
    }

    /** Anything configured that the worker should run for. */
    fun hasWork(context: Context): Boolean =
        serverUrl(context).isNotBlank() && (deviceId(context) != null || ownPairings(context).isNotEmpty() || watches(context).isNotEmpty())

    // ---- pairings ------------------------------------------------------------

    /** A partner watching this phone, app to app. */
    data class OwnPairing(
        val code: String,
        val name: String,
        val createdMs: Long,
        val lastDigestMs: Long,
        val alertSeq: Long,
        val boxReady: Boolean,
    ) {
        val keys: Pairing.Keys by lazy { Pairing.derive(Pairing.decode(code)!!) }
    }

    /** A phone this user is the partner for. */
    data class Watch(
        val code: String,
        val name: String,
        val addedMs: Long,
        val after: Long,
        val lastMsgMs: Long,
        /** Latest digest message, as its LogJson line. */
        val digest: String?,
        /** Recent alerts, newest last: "t\ttext" per line. */
        val alerts: String,
        val staleNotified: Boolean,
    ) {
        val keys: Pairing.Keys by lazy { Pairing.derive(Pairing.decode(code)!!) }
    }

    fun ownPairings(context: Context): List<OwnPairing> = entries(context, OWN).mapNotNull { m ->
        OwnPairing(
            code = m["code"] as? String ?: return@mapNotNull null,
            name = m["name"] as? String ?: "",
            createdMs = m["created"] as? Long ?: 0,
            lastDigestMs = m["digest_at"] as? Long ?: 0,
            alertSeq = m["alert_seq"] as? Long ?: 0,
            boxReady = m["box"] == true,
        )
    }.sortedBy { it.createdMs }

    fun saveOwn(context: Context, p: OwnPairing) {
        prefs(context).edit().putString(
            OWN + p.keys.mailboxId,
            LogJson.encode(linkedMapOf(
                "code" to p.code, "name" to p.name, "created" to p.createdMs,
                "digest_at" to p.lastDigestMs, "alert_seq" to p.alertSeq, "box" to p.boxReady,
            )),
        ).commit()
    }

    fun removeOwn(context: Context, p: OwnPairing) {
        prefs(context).edit().remove(OWN + p.keys.mailboxId).commit()
    }

    fun watches(context: Context): List<Watch> = entries(context, WATCH).mapNotNull { m ->
        Watch(
            code = m["code"] as? String ?: return@mapNotNull null,
            name = m["name"] as? String ?: "",
            addedMs = m["added"] as? Long ?: 0,
            after = m["after"] as? Long ?: 0,
            lastMsgMs = m["last_msg"] as? Long ?: 0,
            digest = m["digest"] as? String,
            alerts = m["alerts"] as? String ?: "",
            staleNotified = m["stale"] == true,
        )
    }.sortedBy { it.addedMs }

    fun saveWatch(context: Context, w: Watch) {
        prefs(context).edit().putString(
            WATCH + w.keys.mailboxId,
            LogJson.encode(linkedMapOf(
                "code" to w.code, "name" to w.name, "added" to w.addedMs, "after" to w.after,
                "last_msg" to w.lastMsgMs, "digest" to w.digest, "alerts" to w.alerts, "stale" to w.staleNotified,
            )),
        ).commit()
    }

    fun removeWatch(context: Context, w: Watch) {
        prefs(context).edit().remove(WATCH + w.keys.mailboxId).commit()
    }

    fun watch(context: Context, mailboxId: String): Watch? = watches(context).firstOrNull { it.keys.mailboxId == mailboxId }

    /** Partner-PIN check against every pairing's current unlock code. */
    fun verifyPartnerCode(context: Context, code: String, now: Long): Boolean =
        ownPairings(context).any { Pairing.verifyCode(it.keys.otp, code, now) }

    private fun entries(context: Context, prefix: String): List<Map<String, Any?>> =
        prefs(context).all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { (it as? String)?.let(LogJson::decode) }
}
