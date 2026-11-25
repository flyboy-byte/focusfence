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

    class Invite(val link: String, val expiresMs: Long)
    class Partner(val id: String, val name: String, val email: String, val sms: Boolean, val inviteId: String = "")
    class AppPartner(val id: String, val name: String, val inviteId: String)
    class Partners(val email: List<Partner>, val app: List<AppPartner>)

    private fun api(ctx: Context): Api {
        val url = SyncStore.serverUrl(ctx)
        // Android blocks cleartext HTTP for this app, so only https:// can work.
        require(url.startsWith("https://")) { "Set an https:// server address first." }
        return Api(url)
    }

    /** Registers this phone's key with the server, once: with the server's enrollment code, or with an [invite] (D-035). */
    fun ensureEnrolled(ctx: Context, invite: String? = null): String {
        SyncStore.deviceId(ctx)?.let { return it }
        val code = SyncStore.enrollCode(ctx)
        val r = api(ctx).post(
            "/v1/enroll", JSONObject().put("publicKeySpki", DeviceKey.publicSpkiB64()),
            when {
                invite != null -> mapOf("X-FF-Invite" to invite)
                code.isNotEmpty() -> mapOf("X-FF-Enroll" to code)
                else -> emptyMap()
            },
        )
        val id = r.body?.optString("deviceId").orEmpty()
        check(r.code != 401) {
            when {
                invite != null -> "That invite was already used or has expired. Ask for a new one."
                code.isNotEmpty() -> "That enrollment code wasn't accepted: it may be mistyped, used up or expired. Ask whoever runs the server for a new one."
                else -> "This server needs an enrollment code. Ask whoever runs it, then paste their message under Join with an invite."
            }
        }
        check(r.code != 507) { "The server is full. Ask whoever runs it." }
        check(r.code == 200 && id.isNotEmpty()) { "Server refused enrollment (HTTP ${r.code})." }
        EventLog.init(ctx)
        SyncStore.setEnrolled(ctx, id, EventLog.currentSeq(EventLog.Channel.ACTIVITY))
        log("server_enrolled", null)
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return id
    }

    /** The body that opens a new mailbox: only an enrolled phone may (D-035). */
    fun mailboxRequest(ctx: Context, mailboxId: String, tokenHash: String): JSONObject =
        signed(ctx, "mailbox", mailboxId).put("tokenHash", tokenHash)

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

    /**
     * D-035: one invite for every kind of partner. Opened in a browser it's the email partner's join page; pasted into
     * Focus Fence on another phone it enrolls that phone and pairs it ([join]). The pairing waits here, unused, until
     * the server says which way the invite went ([resolvePending]).
     */
    fun createInvite(ctx: Context, name: String): Invite {
        val r = api(ctx).post("/v1/invites", signed(ctx, "invite"))
        val b = r.body
        check(r.code == 200 && b != null) { "Couldn't create an invite (HTTP ${r.code})." }
        val key = Pairing.encode(Pairing.generateSecret())
        EventLog.init(ctx)
        SyncStore.saveOwn(ctx, SyncStore.OwnPairing(
            code = key, name = name.trim().take(40), createdMs = System.currentTimeMillis(),
            lastDigestMs = 0, alertSeq = EventLog.currentSeq(EventLog.Channel.ACTIVITY), boxReady = false,
            inviteId = b.optString("inviteId"), pending = true,
        ))
        log("invite_created", name)
        // The report key and pairing key ride after '#', which browsers never send to the server (D-023).
        return Invite(app.focusfence.dns.InviteLink.partnerLink(b.getString("joinUrl"), SyncStore.reportKey(ctx), key), b.getLong("expiresMs"))
    }

    /** A group's way onto this server for its new members (D-035): enrolls a phone, never makes a partner. */
    fun enrollLink(ctx: Context): String {
        val r = api(ctx).post("/v1/invites", signed(ctx, "invite").put("kind", "enroll"))
        check(r.code == 200 && r.body != null) { "Couldn't create an invite (HTTP ${r.code})." }
        return r.body!!.getString("joinUrl")
    }

    /**
     * D-035: the other end of an invite, on the partner's phone. Points this phone at the invite's server, enrolls it
     * with the invite, tells the server it took the invite, and starts watching. Returns the watched person's name.
     */
    fun join(ctx: Context, text: String, myName: String): String {
        val inv = app.focusfence.dns.InviteLink.parse(text) ?: error("That doesn't look like an invite. Paste the whole message.")
        val current = SyncStore.serverUrl(ctx).trimEnd('/')
        if (SyncStore.deviceId(ctx) != null && current != inv.server.trimEnd('/')) {
            error("This phone is on $current and the invite is for ${inv.server}. To move, change the address under Server first; partners on the old server stop hearing from this phone.")
        }
        // Until this phone is on the new server, a failure puts the old address and code back (journey 3, 2026-11-25).
        val wasEnrolled = SyncStore.deviceId(ctx) != null
        val oldCode = SyncStore.enrollCode(ctx)
        SyncStore.setServerUrl(ctx, inv.server)
        try {
            return joinParsed(ctx, inv, myName)
        } catch (e: Exception) {
            if (!wasEnrolled && SyncStore.deviceId(ctx) == null) {
                SyncStore.setServerUrl(ctx, current)
                SyncStore.setEnrollCode(ctx, oldCode)
            }
            throw e
        }
    }

    private fun joinParsed(ctx: Context, inv: app.focusfence.dns.InviteLink.Parsed, myName: String): String {
        return when (inv) {
            is app.focusfence.dns.InviteLink.Parsed.Server -> {
                SyncStore.setEnrollCode(ctx, inv.code)
                ensureEnrolled(ctx)
                ""
            }
            is app.focusfence.dns.InviteLink.Parsed.Partner -> {
                val key = inv.pairingKey ?: error("This invite is from an older version of Focus Fence. Ask for a new one.")
                ensureEnrolled(ctx, invite = inv.secret)
                val body = signed(ctx, "accept", app.focusfence.dns.Pairing.tokenHash(inv.secret)).put("secret", inv.secret).put("name", myName.trim().take(60))
                val r = api(ctx).post("/v1/invites/accept", body)
                check(r.code != 404) { "That invite was already used or has expired. Ask for a new one." }
                check(r.code != 409) { r.body?.optString("error").orEmpty().ifEmpty { "That invite can't be used here." } }
                check(r.code == 200) { "Couldn't join (HTTP ${r.code})." }
                val owner = r.body?.optString("ownerName").orEmpty().ifEmpty { "Partner" }
                // Same report as an email partner: their server page, opened with the key from the invite.
                val report = r.body?.optString("reportUrl").orEmpty().takeIf { it.isNotEmpty() && inv.reportKey != null }
                    ?.let { "$it#k=${inv.reportKey}" }.orEmpty()
                check(addWatch(ctx, owner, key, report)) { "The invite's pairing key doesn't check out." }
                log("invite_joined", owner)
                owner
            }
        }
    }

    /**
     * Pending pairings (an invite not used yet) become real when the server says a phone took that invite, and go
     * when it went to an email partner instead or expired. Run from [SyncWorker].
     */
    fun resolvePending(ctx: Context) {
        val pending = SyncStore.ownPairings(ctx).filter { it.pending }
        if (pending.isEmpty() || SyncStore.deviceId(ctx) == null) return
        val all = partners(ctx)
        val now = System.currentTimeMillis()
        for (p in pending) {
            when {
                all.app.any { it.inviteId == p.inviteId } -> {
                    SyncStore.saveOwn(ctx, p.copy(pending = false))
                    Sync.kick(ctx) // their first alerts and digest go now, not at the next periodic sync
                }
                all.email.any { it.inviteId == p.inviteId } || now - p.createdMs > PENDING_MS -> SyncStore.removeOwn(ctx, p)
            }
        }
    }

    private const val PENDING_MS = 25 * 3_600_000L

    /**
     * Leaves the server (journey 3): partners are told first, while the phone can still reach them, then the enrollment
     * is dropped. Callers put this behind the LEAVE_SERVER guard. [movingTo] names the new server, if any.
     */
    fun leaveServer(ctx: Context, movingTo: String? = null) {
        if (SyncStore.deviceId(ctx) != null) {
            EventLog.init(ctx)
            EventLog.activity(app.focusfence.dns.LogEvents.service(System.currentTimeMillis(), "partner", "server_left",
                movingTo?.let { "moving to another server" }), urgent = true)
            runCatching { SyncWorker.pushAlertsNow(ctx) } // best effort: without it, they still get "went silent"
        }
        SyncStore.clearEnrollment(ctx)
        SyncStore.setServerUrl(ctx, "")
        SyncStore.setEnrollCode(ctx, "")
    }

    /** The first time this phone connects to [server]: what its operator can and can't see. Once per server. */
    fun operatorNoteDue(ctx: Context, server: String): Boolean {
        val p = ctx.getSharedPreferences("focusfence_sync", Context.MODE_PRIVATE)
        val key = "operator_note:" + server.trimEnd('/')
        if (p.getBoolean(key, false)) return false
        p.edit().putBoolean(key, true).apply()
        return true
    }

    /** What to tell a person when talking to the server failed: plain words, not an exception's text. */
    fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Couldn't find that server. Check the address and your internet connection."
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "The server didn't answer. It may be down; try again in a minute."
        is javax.net.ssl.SSLException -> "The server's HTTPS certificate isn't valid, so Focus Fence won't talk to it."
        is java.io.IOException -> "Couldn't reach the server (${e.javaClass.simpleName})."
        else -> e.message ?: e.javaClass.simpleName
    }

    /** A link that gives a partner's browser this phone's report key again (new device, cleared browser). */
    fun keyLink(ctx: Context): String = SyncStore.serverUrl(ctx) + "/key#k=" + SyncStore.reportKey(ctx)

    fun partners(ctx: Context): Partners {
        if (SyncStore.deviceId(ctx) == null) return Partners(emptyList(), emptyList())
        val r = api(ctx).post("/v1/partners/list", signed(ctx, "partners"))
        check(r.code == 200) { "Couldn't load partners (HTTP ${r.code})." }
        val arr = r.body?.optJSONArray("partners")
        val app = r.body?.optJSONArray("app")
        return Partners(
            (0 until (arr?.length() ?: 0)).map { i ->
                val p = arr!!.getJSONObject(i)
                Partner(p.getString("id"), p.getString("name"), p.getString("email"), p.optBoolean("sms"), p.optString("inviteId"))
            },
            (0 until (app?.length() ?: 0)).map { i ->
                val p = app!!.getJSONObject(i)
                AppPartner(p.getString("id"), p.getString("name"), p.optString("inviteId"))
            },
        )
    }

    /** The server tells the removed partner; unlinking is never silent. */
    fun unlink(ctx: Context, partner: Partner) {
        val r = api(ctx).post("/v1/partners/unlink", signed(ctx, "unlink", partner.id, partner.id))
        check(r.code == 200) { "Couldn't remove the partner (HTTP ${r.code})." }
        log("partner_removed", partner.name)
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
        // The server forgets the link too (D-035), so the admin page stops showing them as a partner.
        if (p.inviteId.isNotEmpty()) runCatching {
            partners(ctx).app.firstOrNull { it.inviteId == p.inviteId }?.let { ap ->
                api(ctx).post("/v1/partners/unlink", signed(ctx, "unlink", ap.id, ap.id))
            }
        }
        log("pairing_removed", p.name)
        SyncStore.removeOwn(ctx, p)
        Sync.schedule(ctx)
    }

    /** Starts watching a phone by its pairing key; false if the key doesn't check out. */
    fun addWatch(ctx: Context, name: String, key: String, reportUrl: String = ""): Boolean {
        val secret = Pairing.decode(key) ?: return false
        val code = Pairing.encode(secret)
        if (SyncStore.ownPairings(ctx).any { it.code == code }) return false // that's this phone's own key
        SyncStore.saveWatch(ctx, SyncStore.Watch(
            code = code, name = name.trim().take(40), addedMs = System.currentTimeMillis(),
            after = 0, lastMsgMs = 0, digest = null, alerts = "", staleNotified = false, reportUrl = reportUrl,
        ))
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return true
    }

    private fun log(state: String, detail: String?) {
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", state, detail), urgent = true)
    }
}
