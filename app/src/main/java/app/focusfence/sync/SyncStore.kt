package app.focusfence.sync

import android.content.Context
import android.content.SharedPreferences
import app.focusfence.dns.LogJson

/**
 * Sync state, in its own prefs file: it changes on every upload, and keeping it
 * out of the `focusfence` file means it never wakes the services' rule
 * listeners.
 *
 * Both sides of plan 13's partners live here:
 *  - **this phone, watched**: its server, device id, alert cursor and report key. Who its partners are is the
 *    server's list ([Accountability.partners]); nothing about them is kept here.
 *  - **reports this user sees** ([Seen]): each on its own server, with this phone's device key for it and, once the
 *    owner's phone has sealed it over, that report's key.
 */
object SyncStore {

    private const val FILE = "focusfence_sync"
    private const val KEY_SERVER = "server_url"
    private const val KEY_ENROLL = "enroll_code"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_SEQ = "uploaded_seq"
    private const val KEY_HASH = "last_hash"
    private const val KEY_HEARTBEAT = "last_heartbeat"
    private const val KEY_OK = "last_ok"
    private const val KEY_ERROR = "last_error"
    private const val KEY_REPORT_KEY = "report_key"
    private const val KEY_LAST_REPORT = "last_report_ms"
    private const val SEEN = "seen:"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER, url.trim().trimEnd('/')).apply()
    }

    /** Only needed when the server's operator set one (FF_ENROLL_TOKEN); blank otherwise. Sent when enrolling, nowhere else. */
    fun enrollCode(context: Context): String = prefs(context).getString(KEY_ENROLL, "").orEmpty()

    fun setEnrollCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_ENROLL, code.trim()).apply()
    }

    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE, null)

    /**
     * Uploads start from *now* ([startSeq]), not the whole local history: the
     * partner sees what happens after they're set up. The server doesn't count
     * the jump to the first batch's seq as a gap.
     */
    /** A new server address means a new enrollment there: this phone's id belongs to the old server. */
    /** D-041: the protocol levels our own server last reported. */
    fun setServerProtocol(context: Context, level: Int, oldest: Int) {
        val p = prefs(context)
        if (p.getInt("server_protocol", -1) != level || p.getInt("server_protocol_oldest", -1) != oldest) {
            p.edit().putInt("server_protocol", level).putInt("server_protocol_oldest", oldest).apply()
        }
    }

    fun protocolStatus(context: Context): app.focusfence.dns.Protocol.Status {
        val p = prefs(context)
        val level = p.getInt("server_protocol", -1).takeIf { it > 0 }
        val oldest = p.getInt("server_protocol_oldest", -1).takeIf { it > 0 }
        return app.focusfence.dns.Protocol.check(app.focusfence.dns.Protocol.LEVEL, app.focusfence.dns.Protocol.OLDEST, level, oldest)
    }

    fun clearEnrollment(context: Context) {
        prefs(context).edit().remove(KEY_DEVICE).commit()
    }

    fun setEnrolled(context: Context, deviceId: String?, startSeq: Long) {
        prefs(context).edit().putString(KEY_DEVICE, deviceId).putLong(KEY_SEQ, startSeq).putString(KEY_HASH, "GENESIS").commit()
    }

    /**
     * The key that seals this phone's partner report (D-023). Created on first use and kept only here; partners'
     * devices get it sealed to their own keys (plan 13), so the server never sees it. Clearing app data makes a new
     * one, and the phone seals that over at the next check-in.
     */
    fun reportKey(context: Context): String {
        prefs(context).getString(KEY_REPORT_KEY, null)?.takeIf { app.focusfence.dns.ReportSeal.keyBytes(it) != null }?.let { return it }
        val k = app.focusfence.dns.ReportSeal.newKey()
        prefs(context).edit().putString(KEY_REPORT_KEY, k).commit()
        return k
    }

    /**
     * A new report key: after a partner is removed, leaves, or drops a device, so what they still hold opens only the
     * reports from before. The next heartbeat sends its id, and the server asks for it to be sealed to everyone left.
     */
    fun rotateReportKey(context: Context) {
        prefs(context).edit().putString(KEY_REPORT_KEY, app.focusfence.dns.ReportSeal.newKey()).commit()
    }

    /** What the owner calls themselves in invites ("Logan wants you as a partner"). Empty until asked once. */
    fun ownerName(context: Context): String = prefs(context).getString("owner_name", "").orEmpty()
    fun setOwnerName(context: Context, name: String) { prefs(context).edit().putString("owner_name", name.trim().take(40)).apply() }

    /** When this phone last made an invite: for 30 minutes after, it checks in every minute so a joiner waits under one. */
    fun inviteMadeMs(context: Context): Long = prefs(context).getLong("invite_made", 0)
    fun setInviteMadeMs(context: Context, t: Long) { prefs(context).edit().putLong("invite_made", t).apply() }

    fun lastReportMs(context: Context): Long = prefs(context).getLong(KEY_LAST_REPORT, 0)
    fun setLastReportMs(context: Context, t: Long) { prefs(context).edit().putLong(KEY_LAST_REPORT, t).apply() }

    /** Since D-023 this is the alert cursor: activity records up to here have been checked for alerts. */
    fun uploadedSeq(context: Context): Long = prefs(context).getLong(KEY_SEQ, 0)
    fun lastHash(context: Context): String = prefs(context).getString(KEY_HASH, "GENESIS") ?: "GENESIS"

    fun setCursor(context: Context, seq: Long, hash: String) {
        prefs(context).edit().putLong(KEY_SEQ, seq).putString(KEY_HASH, hash).commit()
    }

    fun lastHeartbeat(context: Context): Long = prefs(context).getLong(KEY_HEARTBEAT, 0)

    /** The newest owner notice (plan 12: a partner left) this phone has shown; the heartbeat asks only for newer ones. */
    fun ownerNoticeT(context: Context): Long = prefs(context).getLong("owner_notice_t", 0)
    fun setOwnerNoticeT(context: Context, t: Long) { prefs(context).edit().putLong("owner_notice_t", t).apply() }
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
        (serverUrl(context).isNotBlank() && deviceId(context) != null) || seen(context).isNotEmpty()

    // ---- reports this user sees (plan 13) -------------------------------------

    /**
     * One report this phone opens as a partner device. [server] and [token] make its link (/p/{token}); [deviceId] is
     * this phone among the partner's devices, [wrappedKey] this phone's private key for it ([PartnerKey]). [reportKey]
     * is empty until the owner's phone has sealed it over; [keyId] says which one it is.
     */
    data class Seen(
        val id: String,
        val server: String,
        val token: String,
        val deviceId: String,
        val wrappedKey: String,
        val owner: String,
        val addedMs: Long,
        val reportKey: String = "",
        val keyId: String = "",
        /** Server time of the newest alert already shown from the feed (/p/{token}/alerts). */
        val serverAlertT: Long = 0,
        /** Recent alerts, newest last: "t\ttext" per line. */
        val alerts: String = "",
        /** Plan 12 §5: alerts held for quiet hours or the daily message ("t\ttext" per line), and the day last sent. */
        val held: String = "",
        val heldDay: Long = 0,
        /** Their server says this report link no longer exists (404): they removed you, or you left elsewhere. */
        val linkGone: Boolean = false,
    ) {
        val reportUrl: String get() = "$server/p/$token"
    }

    fun seen(context: Context): List<Seen> = entries(context, SEEN).mapNotNull { m ->
        Seen(
            id = m["id"] as? String ?: return@mapNotNull null,
            server = m["server"] as? String ?: return@mapNotNull null,
            token = m["token"] as? String ?: return@mapNotNull null,
            deviceId = m["device"] as? String ?: return@mapNotNull null,
            wrappedKey = m["wrapped"] as? String ?: return@mapNotNull null,
            owner = m["owner"] as? String ?: "",
            addedMs = m["added"] as? Long ?: 0,
            reportKey = m["report_key"] as? String ?: "",
            keyId = m["key_id"] as? String ?: "",
            serverAlertT = m["server_alert_t"] as? Long ?: 0,
            alerts = m["alerts"] as? String ?: "",
            held = m["held"] as? String ?: "",
            heldDay = m["held_day"] as? Long ?: 0,
            linkGone = m["link_gone"] == true,
        )
    }.sortedBy { it.addedMs }

    fun seen(context: Context, id: String): Seen? = seen(context).firstOrNull { it.id == id }

    fun saveSeen(context: Context, s: Seen) {
        prefs(context).edit().putString(
            SEEN + s.id,
            LogJson.encode(linkedMapOf(
                "id" to s.id, "server" to s.server, "token" to s.token, "device" to s.deviceId, "wrapped" to s.wrappedKey,
                "owner" to s.owner, "added" to s.addedMs, "report_key" to s.reportKey, "key_id" to s.keyId,
                "server_alert_t" to s.serverAlertT, "alerts" to s.alerts, "held" to s.held, "held_day" to s.heldDay, "link_gone" to s.linkGone,
            )),
        ).commit()
    }

    fun removeSeen(context: Context, s: Seen) {
        prefs(context).edit().remove(SEEN + s.id).commit()
    }

    /**
     * Plan 13 clean cut: the partner data of every earlier version (pairings this phone made, phones it watched, the
     * group). Deleted, never converted. Returns how many entries went, so the caller can tell the person once.
     */
    fun clearOldPartners(context: Context): Int {
        val p = prefs(context)
        val old = p.all.keys.filter { it.startsWith("own:") || it.startsWith("watch:") }
        if (old.isNotEmpty()) p.edit().apply { old.forEach { remove(it) } }.commit()
        return old.size
    }

    private fun entries(context: Context, prefix: String): List<Map<String, Any?>> =
        prefs(context).all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { (it as? String)?.let(LogJson::decode) }
}
