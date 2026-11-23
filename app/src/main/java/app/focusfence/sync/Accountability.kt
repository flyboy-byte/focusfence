package app.focusfence.sync

import android.content.Context
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.Pairing
import org.json.JSONObject

/**
 * The partner operations the UI calls (all blocking: run off the main thread).
 * Every change of who can see this phone is itself logged, so the record shows
 * when a partner was added or removed.
 */
object Accountability {

    class Invite(val code: String, val joinUrl: String, val expiresMs: Long)
    class Partner(val id: String, val name: String, val email: String, val sms: Boolean)

    private fun api(ctx: Context): Api {
        val url = SyncStore.serverUrl(ctx)
        // Android blocks cleartext HTTP for this app, so only https:// can work.
        require(url.startsWith("https://")) { "Set an https:// server address first." }
        return Api(url)
    }

    /** Registers this phone's key with the server, once. */
    fun ensureEnrolled(ctx: Context): String {
        SyncStore.deviceId(ctx)?.let { return it }
        val code = SyncStore.enrollCode(ctx)
        val r = api(ctx).post(
            "/v1/enroll", JSONObject().put("publicKeySpki", DeviceKey.publicSpkiB64()),
            if (code.isNotEmpty()) mapOf("X-FF-Enroll" to code) else emptyMap(),
        )
        val id = r.body?.optString("deviceId").orEmpty()
        check(r.code != 401) { "This server needs an enrollment code. Ask whoever runs it, then enter it under Server." }
        check(r.code != 507) { "The server is full. Ask whoever runs it." }
        check(r.code == 200 && id.isNotEmpty()) { "Server refused enrollment (HTTP ${r.code})." }
        EventLog.init(ctx)
        SyncStore.setEnrolled(ctx, id, EventLog.currentSeq(EventLog.Channel.ACTIVITY))
        log("server_enrolled", null)
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return id
    }

    private fun signed(ctx: Context, action: String, extra: String = "", partnerId: String? = null): JSONObject {
        val id = ensureEnrolled(ctx)
        val t = System.currentTimeMillis()
        return JSONObject().apply {
            put("deviceId", id)
            put("t", t)
            put("signature", DeviceKey.sign("$action|$id|$t|$extra".toByteArray()))
            if (partnerId != null) put("partnerId", partnerId)
        }
    }

    fun createInvite(ctx: Context): Invite {
        val r = api(ctx).post("/v1/invites", signed(ctx, "invite"))
        val b = r.body
        check(r.code == 200 && b != null) { "Couldn't create an invite (HTTP ${r.code})." }
        log("invite_created", null)
        return Invite(b.getString("code"), b.getString("joinUrl"), b.getLong("expiresMs"))
    }

    fun partners(ctx: Context): List<Partner> {
        if (SyncStore.deviceId(ctx) == null) return emptyList()
        val r = api(ctx).post("/v1/partners/list", signed(ctx, "partners"))
        val arr = r.body?.optJSONArray("partners") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Partner(p.getString("id"), p.getString("name"), p.getString("email"), p.optBoolean("sms"))
        }
    }

    /** The server tells the removed partner; unlinking is never silent. */
    fun unlink(ctx: Context, partner: Partner) {
        val r = api(ctx).post("/v1/partners/unlink", signed(ctx, "unlink", partner.id, partner.id))
        check(r.code == 200) { "Couldn't remove the partner (HTTP ${r.code})." }
        log("partner_removed", partner.name)
    }

    /** A new app-to-app pairing; returns the key to send to the partner. */
    fun newPairing(ctx: Context, name: String): String {
        val code = Pairing.encode(Pairing.generateSecret())
        EventLog.init(ctx)
        SyncStore.saveOwn(ctx, SyncStore.OwnPairing(
            code = code, name = name.trim().take(40), createdMs = System.currentTimeMillis(),
            lastDigestMs = 0, alertSeq = EventLog.currentSeq(EventLog.Channel.ACTIVITY), boxReady = false,
        ))
        log("pairing_created", name)
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return code
    }

    /**
     * Removing a pairing tells the partner first (an alert in their app), then
     * forgets the key — after which this phone can't post to them at all.
     */
    fun removePairing(ctx: Context, p: SyncStore.OwnPairing) {
        val k = p.keys
        val sealed = Pairing.seal(
            k.enc,
            app.focusfence.dns.LogJson.encode(app.focusfence.dns.Digest.alert(System.currentTimeMillis(), "You were removed as a partner on this phone")).toByteArray(),
            k.mailboxId.toByteArray(),
        )
        // Best effort: if this fails, their app still flags the silence (STALE_AFTER_MS).
        runCatching {
            api(ctx).post("/v1/mailbox/${k.mailboxId}/msgs", JSONObject().put("c", app.focusfence.dns.B64.encode(sealed)), mapOf("X-FF-Token" to k.token))
        }
        log("pairing_removed", p.name)
        SyncStore.removeOwn(ctx, p)
        Sync.schedule(ctx)
    }

    /** Starts watching a phone by its pairing key; false if the key doesn't check out. */
    fun addWatch(ctx: Context, name: String, key: String): Boolean {
        val secret = Pairing.decode(key) ?: return false
        val code = Pairing.encode(secret)
        if (SyncStore.ownPairings(ctx).any { it.code == code }) return false // that's this phone's own key
        SyncStore.saveWatch(ctx, SyncStore.Watch(
            code = code, name = name.trim().take(40), addedMs = System.currentTimeMillis(),
            after = 0, lastMsgMs = 0, digest = null, alerts = "", staleNotified = false,
        ))
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return true
    }

    private fun log(state: String, detail: String?) {
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", state, detail), urgent = true)
    }
}
