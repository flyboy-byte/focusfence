package app.focusfence.sync

import android.content.Context
import app.focusfence.data.EventLog
import app.focusfence.dns.InviteLink
import app.focusfence.dns.LogEvents
import org.json.JSONObject

/**
 * The partner operations the UI calls (all blocking: run off the main thread). Plan 13: one kind of partner, a person
 * with devices. Every change of who can see this phone is itself logged, so the record shows when a partner was added
 * or removed.
 */
object Accountability {

    class Invite(val id: String, val code: String, val qrUrl: String, val expiresMs: Long, val emailed: Boolean)
    class Device(val id: String, val kind: String, val label: String, val addedMs: Long, val ready: Boolean)
    /** [lastViewMs]: when they last opened the report (plan 12 §2), 0 never. [alertsBy]: "app", "email" or "none". */
    class Partner(val id: String, val name: String, val email: String, val alertsBy: String, val lastViewMs: Long, val devices: List<Device>,
                  val groups: List<String> = emptyList(), val chosen: Boolean = true) {
        val ready: Boolean get() = devices.any { it.ready }
    }
    class OpenInvite(val id: String, val expiresMs: Long, val email: String)
    class Partners(val partners: List<Partner>, val invites: List<OpenInvite>)

    private fun api(ctx: Context): Api {
        val url = SyncStore.serverUrl(ctx)
        // Android blocks cleartext HTTP for this app, so only https:// can work.
        require(url.startsWith("https://")) { "Set an https:// server address first." }
        return Api(url)
    }

    /** Registers this phone's key with the server, once, with the server's enrollment code if it has one. */
    fun ensureEnrolled(ctx: Context, groupCode: String = ""): String {
        SyncStore.deviceId(ctx)?.let { return it }
        val code = SyncStore.enrollCode(ctx)
        // Being someone's partner on this server is a credential too: no enrollment code needed.
        val token = SyncStore.seen(ctx).firstOrNull { it.server.trimEnd('/') == SyncStore.serverUrl(ctx).trimEnd('/') && !it.linkGone }?.token
        val r = api(ctx).post(
            "/v1/enroll", JSONObject().put("publicKeySpki", DeviceKey.publicSpkiB64()),
            buildMap {
                if (code.isNotEmpty()) put("X-FF-Enroll", code)
                if (token != null) put("X-FF-Partner", token)
                if (groupCode.isNotEmpty()) put("X-FF-Group", groupCode)
            },
        )
        val id = r.body?.optString("deviceId").orEmpty()
        check(r.code != 426) { r.body?.optString("error").orEmpty().ifEmpty { "Update Focus Fence: this server needs a newer version." } }
        check(r.code != 401) {
            if (code.isNotEmpty()) "That enrollment code wasn't accepted: it may be mistyped, used up or expired. Ask whoever runs the server for a new one."
            else "This server needs an enrollment code. Ask whoever runs it, and type it under Server."
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

    /**
     * There are two kinds of phone, on a server or not: a phone that joined someone's report is on that server too, so
     * it can be watched later without an enrollment code. Runs after joining, and at sync for phones that joined
     * before 3.0.1. Never moves a phone that is already on another server.
     */
    fun enrollAsPartner(ctx: Context) {
        if (SyncStore.deviceId(ctx) != null) return
        val s = SyncStore.seen(ctx).firstOrNull { !it.linkGone } ?: return
        val own = SyncStore.serverUrl(ctx).trimEnd('/')
        if (own.isNotEmpty() && own != s.server.trimEnd('/')) return
        if (own.isEmpty()) SyncStore.setServerUrl(ctx, s.server.trimEnd('/'))
        ensureEnrolled(ctx)
    }

    private fun signed(ctx: Context, action: String, extra: String = ""): JSONObject {
        val id = ensureEnrolled(ctx)
        val t = System.currentTimeMillis()
        return JSONObject().apply {
            put("deviceId", id)
            put("t", t)
            put("signature", DeviceKey.sign("$action|$id|$t|$extra".toByteArray()))
        }
    }

    /**
     * Plan 13: an invite, used once within 24 hours. Always a code (and its QR code); with an [email], the server also
     * emails that address a link of its own. While it's open this phone checks in every minute, so whoever joins gets
     * the report key within about one.
     */
    fun createInvite(ctx: Context, email: String = ""): Invite {
        val e = email.trim()
        val r = api(ctx).post("/v1/invites", signed(ctx, "invite", e).put("email", e).put("from", SyncStore.ownerName(ctx)))
        val b = r.body
        check(r.code == 200 && b != null) { b?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Couldn't create an invite (HTTP ${r.code})." }
        SyncStore.setInviteMadeMs(ctx, System.currentTimeMillis())
        log("invite_created", if (e.isEmpty()) "code" else "email")
        Sync.kickAfter(ctx, "invite_${System.currentTimeMillis() / 60_000}", 60_000)
        return Invite(b.getString("id"), b.getString("code"), b.getString("qrUrl"), b.getLong("expiresMs"), b.optBoolean("emailed"))
    }

    fun cancelInvite(ctx: Context, id: String) {
        val r = api(ctx).post("/v1/invites/cancel", signed(ctx, "invite_cancel", id).put("inviteId", id))
        check(r.code == 200) { "Couldn't cancel the invite (HTTP ${r.code})." }
    }

    /**
     * Plan 13, the partner's side: uses an invite ([text] is what they typed, scanned or opened: a code, or a /c/ or /j/
     * link) to join from this phone. This phone makes its own key pair for that report and sends the public half; the
     * owner's phone seals the report key to it. Returns the owner's name. [server] is needed only for a bare code.
     */
    fun join(ctx: Context, text: String, myName: String, server: String = ""): String {
        // The admin page's message ("Server: … Enrollment code: …") pasted here connects this phone as the watched one.
        (InviteLink.parse(text) as? InviteLink.Parsed.Server)?.let { s ->
            check(SyncStore.deviceId(ctx) == null || SyncStore.serverUrl(ctx).trimEnd('/') == s.server.trimEnd('/')) {
                "This phone is on ${SyncStore.serverUrl(ctx)}. To move, change the address under Server first."
            }
            SyncStore.setServerUrl(ctx, s.server)
            SyncStore.setEnrollCode(ctx, s.code)
            ensureEnrolled(ctx)
            return ""
        }
        val parsed = InviteLink.parse(text) as? InviteLink.Parsed.Join
            ?: InviteLink.normalizeCode(text).takeIf { it.length == 8 }?.let { code ->
                val srv = InviteLink.server(server) ?: error("Type the server too: it's under the code on their phone.")
                InviteLink.Parsed.Join(srv, code = code)
            }
            ?: error("Type the 8-character code from their phone.")
        val key = PartnerKey.create()
        val label = android.os.Build.MODEL?.take(40).orEmpty().ifEmpty { "Phone" }
        val body = JSONObject().put("code", parsed.code).put("link", parsed.link).put("name", myName.trim().take(60))
            .put("kind", "app").put("label", label).put("publicKeySpki", key.publicSpkiB64)
        val r = Api(parsed.server).post("/v1/join", body)
        val b = r.body
        check(r.code != 426) { "That server is newer than this app. Update Focus Fence." }
        check(r.code == 200 && b != null) { b?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Couldn't join (HTTP ${r.code})." }
        val owner = b.optString("ownerName").ifEmpty { "Partner" }.take(40)
        SyncStore.saveSeen(ctx, SyncStore.Seen(
            id = b.getString("partnerId"), server = parsed.server, token = b.getString("token"), deviceId = b.getString("deviceId"),
            wrappedKey = key.wrapped, owner = owner, addedMs = System.currentTimeMillis(),
        ))
        log("joined", owner)
        runCatching { enrollAsPartner(ctx) } // best effort: sync tries again
        // The key comes at the owner phone's next check-in, about a minute while their invite was open: look every minute.
        Sync.schedule(ctx)
        Sync.kick(ctx)
        return owner
    }

    /** Stops seeing a report: the server is told first (the owner and their other partners hear it), then it's forgotten. */
    fun leave(ctx: Context, s: SyncStore.Seen) {
        // Best effort: offline, the server isn't told and still lists this partner until the owner removes them.
        if (!s.linkGone) runCatching { Api(s.server).post("/p/${s.token}/leave", JSONObject()) }
        SyncStore.removeSeen(ctx, s)
        log("left", s.owner)
        Sync.schedule(ctx)
    }

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

    fun partners(ctx: Context): Partners {
        if (SyncStore.deviceId(ctx) == null) return Partners(emptyList(), emptyList())
        val r = api(ctx).post("/v1/partners/list", signed(ctx, "partners"))
        check(r.code == 200) { "Couldn't load partners (HTTP ${r.code})." }
        val arr = r.body?.optJSONArray("partners")
        val inv = r.body?.optJSONArray("invites")
        return Partners(
            (0 until (arr?.length() ?: 0)).map { i ->
                val p = arr!!.getJSONObject(i)
                val ds = p.optJSONArray("devices")
                Partner(p.getString("id"), p.getString("name"), p.optString("email"), p.optString("alertsBy"), p.optLong("lastViewMs"),
                    (0 until (ds?.length() ?: 0)).map { j -> ds!!.getJSONObject(j).let { d ->
                        Device(d.getString("id"), d.optString("kind"), d.optString("label"), d.optLong("addedMs"), d.optBoolean("ready"))
                    } },
                    p.optJSONArray("groups").let { g -> (0 until (g?.length() ?: 0)).map { g!!.getString(it) } }, p.optBoolean("chosen", true))
            },
            (0 until (inv?.length() ?: 0)).map { i -> inv!!.getJSONObject(i).let { OpenInvite(it.getString("id"), it.optLong("expiresMs"), it.optString("email")) } },
        )
    }

    /**
     * Plan 12 §3: asks every partner for an OK to [action]. Null on success, else a sentence for the owner. Blocking;
     * call off the main thread. The answer arrives with the heartbeat ([SyncWorker]), checked every minute meanwhile.
     */
    fun askPartner(ctx: Context, action: app.focusfence.dns.GuardPolicy.Action): String? {
        if (SyncStore.deviceId(ctx) == null) return ctx.getString(app.focusfence.R.string.ask_no_server)
        val r = try {
            api(ctx).post("/v1/unlock/ask", signed(ctx, "unlock", action.key).put("kind", action.key))
        } catch (e: Exception) {
            EventLog.diag(LogEvents.error(System.currentTimeMillis(), "ask", "network", e.javaClass.simpleName))
            return friendly(e)
        }
        if (r.code != 200) EventLog.diag(LogEvents.error(System.currentTimeMillis(), "ask", "http_${r.code}", action.name.lowercase()))
        return when (r.code) {
            200 -> {
                val b = r.body ?: return ctx.getString(app.focusfence.R.string.ask_failed, r.code)
                app.focusfence.data.ApprovalStore.save(ctx, app.focusfence.data.ApprovalStore.Ask(
                    b.optString("id"), b.optString("action"), b.optString("state"), b.optString("by"), b.optLong("askedMs"), b.optLong("decidedMs"),
                ))
                app.focusfence.data.StrictStore.note(ctx, "partner_asked", action.name.lowercase())
                Sync.kickAfter(ctx, "unlock_${System.currentTimeMillis() / 60_000}", 60_000)
                null
            }
            409 -> ctx.getString(app.focusfence.R.string.ask_no_partner)
            404 -> ctx.getString(app.focusfence.R.string.ask_server_old)
            else -> ctx.getString(app.focusfence.R.string.ask_failed, r.code)
        }
    }

    /**
     * Removes a partner: the server forgets them (and emails them, if they have email; their app finds its link gone),
     * and this phone starts a new report key at once, so their devices can't open anything new.
     */
    fun unlink(ctx: Context, partner: Partner) {
        val r = api(ctx).post("/v1/partners/unlink", signed(ctx, "unlink", partner.id).put("partnerId", partner.id))
        check(r.code == 200 || r.code == 404) { "Couldn't remove the partner (HTTP ${r.code})." }
        SyncStore.rotateReportKey(ctx)
        log("partner_removed", partner.name)
        Sync.kick(ctx)
    }

    // ---- groups (plan 14) -----------------------------------------------------

    class GroupMember(val name: String, val me: Boolean, val joinedMs: Long, val theirToken: String, val lastViewMs: Long)
    class Group(val id: String, val name: String, val members: List<GroupMember>, val codeExpiresMs: Long, val max: Int, val level: String) {
        val spots: Int get() = max - members.size
    }
    class GroupCode(val groupId: String, val name: String, val code: String, val expiresMs: Long, val qrUrl: String)
    /** What a code is before joining with it: [kind] "group" (with [members]) or "invite" (from [name]). */
    class Lookup(val kind: String, val name: String, val members: List<String>, val full: Boolean)

    private fun fail(r: Api.Response, what: String): Nothing =
        error(r.body?.optString("error")?.takeIf { it.isNotEmpty() } ?: "$what (HTTP ${r.code}).")

    private fun groupCode(b: JSONObject) = GroupCode(b.getString("groupId"), b.optString("name"), b.getString("code"), b.getLong("expiresMs"), b.getString("qrUrl"))

    private fun group(o: JSONObject): Group {
        val ms = o.optJSONArray("members")
        return Group(o.getString("id"), o.optString("name"), (0 until (ms?.length() ?: 0)).map { i -> ms!!.getJSONObject(i).let { m ->
            GroupMember(m.optString("name"), m.optBoolean("me"), m.optLong("joinedMs"), m.optString("theirToken"), m.optLong("lastViewMs"))
        } }, o.optLong("codeExpiresMs"), o.optInt("max", 6), o.optString("level"))
    }

    /** The server a typed code belongs to: a link carries its own, a bare code uses [server] or this phone's. */
    private fun codeServer(ctx: Context, server: String): String =
        InviteLink.server(server) ?: SyncStore.serverUrl(ctx).takeIf { it.startsWith("https://") } ?: error("Type the server too: it's under the code on their phone.")

    /** Whether [code] is a group's or a partner invite, before joining. Null when the server doesn't know it. */
    fun lookup(ctx: Context, code: String, server: String): Lookup? {
        val r = Api(codeServer(ctx, server)).post("/v1/codes/lookup", JSONObject().put("code", InviteLink.normalizeCode(code)))
        if (r.code == 404) return null
        val b = r.body
        if (r.code != 200 || b == null) fail(r, "Couldn't check the code")
        val ms = b.optJSONArray("members")
        return Lookup(b.optString("kind"), b.optString("name"), (0 until (ms?.length() ?: 0)).map { ms!!.getString(it) }, b.optBoolean("full"))
    }

    fun groups(ctx: Context): List<Group> {
        if (SyncStore.deviceId(ctx) == null) return emptyList()
        val r = api(ctx).post("/v1/groups/list", signed(ctx, "groups"))
        if (r.code != 200) fail(r, "Couldn't load groups")
        val arr = r.body?.optJSONArray("groups")
        return (0 until (arr?.length() ?: 0)).map { group(arr!!.getJSONObject(it)) }
    }

    fun createGroup(ctx: Context, name: String): GroupCode {
        val n = name.trim().take(40)
        val r = api(ctx).post("/v1/groups/create", signed(ctx, "group_create", n).put("name", n).put("label", SyncStore.ownerName(ctx)))
        val b = r.body
        if (r.code != 200 || b == null) fail(r, "Couldn't start the group")
        log("group_created", n)
        SyncStore.setInviteMadeMs(ctx, System.currentTimeMillis())
        Sync.kickAfter(ctx, "group_${System.currentTimeMillis() / 60_000}", 60_000)
        return groupCode(b)
    }

    /** A fresh code for one more person (the old one stops working). */
    fun newGroupCode(ctx: Context, groupId: String): GroupCode {
        val r = api(ctx).post("/v1/groups/code", signed(ctx, "group_code", groupId).put("groupId", groupId))
        val b = r.body
        if (r.code != 200 || b == null) fail(r, "Couldn't make a code")
        SyncStore.setInviteMadeMs(ctx, System.currentTimeMillis())
        return groupCode(b)
    }

    /**
     * Joins a group from this phone: it must be on that server, since every member is watched, so a phone not on one yet
     * enrolls there with the group code. Never moves a phone that is on another server.
     */
    fun joinGroup(ctx: Context, code: String, server: String, myName: String): Group {
        val srv = codeServer(ctx, server).trimEnd('/')
        val own = SyncStore.serverUrl(ctx).trimEnd('/')
        check(SyncStore.deviceId(ctx) == null || own == srv) {
            "This phone is on ${own.removePrefix("https://")}, and the group is on ${srv.removePrefix("https://")}. A phone is on one server."
        }
        if (SyncStore.deviceId(ctx) == null) SyncStore.setServerUrl(ctx, srv)
        val c = InviteLink.normalizeCode(code)
        ensureEnrolled(ctx, c)
        if (myName.isNotBlank() && SyncStore.ownerName(ctx).isBlank()) SyncStore.setOwnerName(ctx, myName.trim().take(40))
        val r = api(ctx).post("/v1/groups/join", signed(ctx, "group_join", c).put("code", c).put("label", myName.trim().take(40)))
        val b = r.body
        if (r.code != 200 || b == null) fail(r, "Couldn't join the group")
        val g = group(b)
        log("group_joined", g.name)
        Sync.kick(ctx)
        return g
    }

    /** Leaves a group: every pair it made goes both ways (pairs another group or an invite also made stay). */
    fun leaveGroup(ctx: Context, g: Group) {
        val r = api(ctx).post("/v1/groups/leave", signed(ctx, "group_leave", g.id).put("groupId", g.id))
        if (r.code != 200 && r.code != 404) fail(r, "Couldn't leave the group")
        log("group_left", g.name)
        SyncStore.rotateReportKey(ctx)
        Sync.kick(ctx)
    }

    /** How this phone hears about the group's members (plan 12 levels: all, important, daily). */
    fun setGroupLevel(ctx: Context, g: Group, level: String) {
        val r = api(ctx).post("/v1/groups/level", signed(ctx, "group_level", "${g.id}|$level").put("groupId", g.id).put("level", level))
        if (r.code != 200) fail(r, "Couldn't save")
        log("group_alerts", "${g.name}: $level")
    }

    private fun log(state: String, detail: String?) {
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", state, detail), urgent = true)
    }
}
