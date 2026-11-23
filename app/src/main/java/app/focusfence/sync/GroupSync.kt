package app.focusfence.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.focusfence.GroupActivity
import app.focusfence.R
import app.focusfence.data.EventLog
import app.focusfence.data.GroupStore
import app.focusfence.dns.B64
import app.focusfence.dns.Group
import app.focusfence.dns.GroupInvite
import app.focusfence.dns.GroupProtocol
import app.focusfence.dns.GroupProtocol.Action
import app.focusfence.dns.LogEvents
import app.focusfence.dns.Pairing
import org.json.JSONObject

/**
 * One group-sync pass, run from [SyncWorker]: read the group's blind mailbox, replay it ([GroupProtocol.replay]),
 * do what [GroupProtocol.plan] says (make or drop pairings, start or stop watching), send the outbox, and tell the
 * person about changes. The group only carries membership and sealed hand-overs; the reports themselves keep going
 * over the ordinary per-pair mailboxes ([SyncWorker.sendToPartner] / `receive`).
 */
object GroupSync {

    private const val CHANNEL = "group_alerts"
    private const val PAGE = 200

    fun run(ctx: Context, api: Api, now: Long) {
        var st = GroupStore.load(ctx) ?: return
        val group = Pairing.derive(Pairing.decode(st.code) ?: return)
        val groupId = group.mailboxId
        val token = mapOf("X-FF-Token" to group.token)

        if (!st.boxReady) {
            val r = api.post("/v1/mailbox/$groupId", JSONObject().put("tokenHash", Pairing.tokenHash(group.token)))
            if (r.code != 200) throw IllegalStateException("group mailbox HTTP ${r.code}")
            st = st.copy(boxReady = true)
            GroupStore.save(ctx, st)
        }

        // 1. read everything new
        while (true) {
            val r = api.get("/v1/mailbox/$groupId/msgs?after=${st.cursor}", token)
            if (r.code == 404) break
            if (r.code != 200) throw IllegalStateException("group mailbox read HTTP ${r.code}")
            val msgs = r.body?.optJSONArray("msgs") ?: break
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                GroupProtocol.openB64(group, m.optString("c"))?.let { GroupStore.appendLog(ctx, it) } // junk is skipped, never fatal
                st = st.copy(cursor = maxOf(st.cursor, m.optLong("id")))
            }
            GroupStore.save(ctx, st)
            if (msgs.length() < PAGE) break
        }

        // 2. replay and act
        val view = GroupProtocol.replay(groupId, GroupStore.readLog(ctx))
        val me = st.identity
        val state = view.state
        val isMember = state?.members?.any { it.id == me.memberId } == true

        if (state != null && me.memberId in view.keys && !isMember && !st.ended) {
            // we were in the group and now aren't: removed by someone
            st = endMembership(ctx, st, ctx.getString(R.string.group_removed_you))
            GroupStore.save(ctx, st)
        }

        if (st.autoApproveCreator && view.creatorId != null && view.creatorId != me.memberId && view.creatorId !in st.approved) {
            st = st.copy(approved = st.approved + view.creatorId)
        }

        if (isMember && !st.ended) {
            var offered = st.offered; var applied = st.applied; var watching = st.watching
            var pairings = st.pairings; var watches = st.watches
            for (a in GroupProtocol.plan(view, groupId, me, st.local, now)) when (a) {
                is Action.Post -> GroupStore.addOutbox(ctx, a.msg)
                is Action.PairFor -> {
                    SyncStore.saveOwn(ctx, SyncStore.OwnPairing(
                        code = a.code, name = a.viewerName, createdMs = now, lastDigestMs = 0,
                        alertSeq = EventLog.currentSeq(EventLog.Channel.ACTIVITY), boxReady = false,
                    ))
                    pairings = pairings + (a.viewerId to a.code); offered = offered + a.viewerId
                }
                is Action.Watch -> {
                    watches[a.ownerId]?.let { old -> removeWatchByCode(ctx, old) } // a fresh hand-over replaces the old one
                    SyncStore.saveWatch(ctx, SyncStore.Watch(
                        code = a.code, name = a.ownerName, addedMs = now, after = 0, lastMsgMs = 0,
                        digest = null, alerts = "", staleNotified = false,
                    ))
                    watches = watches + (a.ownerId to a.code); applied = applied + a.offerId; watching = watching + a.ownerId
                }
                is Action.Unpair -> {
                    pairings[a.viewerId]?.let { removeOwnByCode(ctx, it) }
                    pairings = pairings - a.viewerId; offered = offered - a.viewerId
                }
                is Action.Unwatch -> {
                    watches[a.ownerId]?.let { removeWatchByCode(ctx, it) }
                    watches = watches - a.ownerId; watching = watching - a.ownerId
                }
            }
            st = st.copy(offered = offered, applied = applied, watching = watching, pairings = pairings, watches = watches)
        }
        GroupStore.save(ctx, st)

        // 3. send what is waiting (offline-safe: it stays until the server takes it)
        val outbox = GroupStore.outbox(ctx)
        var sent = 0
        for (msg in outbox) {
            val r = api.post(
                "/v1/mailbox/$groupId/msgs",
                JSONObject().put("c", B64.encode(GroupProtocol.seal(group, msg))), token,
            )
            if (r.code != 200) break
            sent++
        }
        if (sent > 0) GroupStore.setOutbox(ctx, outbox.drop(sent))
        if (sent > 0) Sync.kick(ctx) // let everyone else see it soon

        // 4. tell the person, and keep the integrity trail honest
        val fresh = view.notices.drop(st.noticesSeen)
        for (n in fresh) {
            EventLog.activity(LogEvents.service(now, "partner", "group_notice", n), urgent = true)
            notify(ctx, 7_000 + st.noticesSeen, ctx.getString(R.string.group_notice_title), n)
        }
        val waiting = GroupProtocol.awaitingApproval(view, me, st.local).filter { it.first.id !in st.prompted }
        for ((m, _) in waiting) notify(ctx, 8_000 + (m.id.hashCode() and 0xfff), ctx.getString(R.string.group_approve_title), ctx.getString(R.string.group_approve_text, m.name))
        st = st.copy(noticesSeen = st.noticesSeen + fresh.size, prompted = st.prompted + waiting.map { it.first.id })
        GroupStore.save(ctx, st)

        // 5. a phone that left (or was removed) lets go of every pairing it made for the group, then forgets the group
        if (st.ended && GroupStore.outbox(ctx).isEmpty()) {
            for (code in st.pairings.values) removeOwnByCode(ctx, code)
            for (code in st.watches.values) removeWatchByCode(ctx, code)
            GroupStore.clear(ctx)
        }
    }

    /** This phone is out of the group: keep the state long enough to tell the person, revoke pairings on the next pass. */
    fun endMembership(ctx: Context, st: GroupStore.State, why: String): GroupStore.State {
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", "group_notice", why), urgent = true)
        notify(ctx, 7_999, ctx.getString(R.string.group_notice_title), why)
        return st.copy(ended = true)
    }

    private fun removeOwnByCode(ctx: Context, code: String) {
        SyncStore.ownPairings(ctx).firstOrNull { it.code == code }?.let { SyncStore.removeOwn(ctx, it) }
    }

    private fun removeWatchByCode(ctx: Context, code: String) {
        SyncStore.watches(ctx).firstOrNull { it.code == code }?.let { SyncStore.removeWatch(ctx, it) }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.group_channel_name), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, GroupActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}

/**
 * What the group screen calls. Nothing here touches the network: each action signs a message, puts it in the outbox
 * and asks the sync worker to run; so it works offline and never blocks the UI thread.
 */
object GroupManager {

    sealed class Result {
        data class Ok(val invite: String? = null) : Result()
        data class Error(val message: String) : Result()
    }

    fun create(ctx: Context, name: String, role: Group.Role): Result {
        if (GroupStore.active(ctx)) return Result.Error(ctx.getString(R.string.group_already))
        val server = SyncStore.serverUrl(ctx)
        if (!server.startsWith("https://")) return Result.Error(ctx.getString(R.string.acc_need_server))
        val n = name.filter { !it.isISOControl() }.trim().take(Group.MAX_NAME)
        if (n.isEmpty()) return Result.Error(ctx.getString(R.string.group_need_name))
        val code = Pairing.encode(Pairing.generateSecret())
        val st = GroupStore.newState(ctx, code, n, role, joiner = false)
        post(ctx) { GroupProtocol.hello(st.identity, Pairing.derive(Pairing.decode(code)!!).mailboxId, role, now()) }
        log(ctx, "group_created")
        return Result.Ok(GroupInvite.format(code, server))
    }

    fun join(ctx: Context, inviteText: String, name: String, role: Group.Role): Result {
        if (GroupStore.active(ctx)) return Result.Error(ctx.getString(R.string.group_already))
        val inv = GroupInvite.parse(inviteText) ?: return Result.Error(ctx.getString(R.string.group_bad_invite))
        val n = name.filter { !it.isISOControl() }.trim().take(Group.MAX_NAME)
        if (n.isEmpty()) return Result.Error(ctx.getString(R.string.group_need_name))
        if (SyncStore.serverUrl(ctx).isBlank() && inv.server != null) SyncStore.setServerUrl(ctx, inv.server)
        if (!SyncStore.serverUrl(ctx).startsWith("https://")) return Result.Error(ctx.getString(R.string.acc_need_server))
        if (inv.server != null && inv.server.trimEnd('/') != SyncStore.serverUrl(ctx).trimEnd('/')) {
            return Result.Error(ctx.getString(R.string.group_other_server, inv.server))
        }
        val code = Pairing.encode(inv.secret)
        val st = GroupStore.newState(ctx, code, n, role, joiner = true)
        post(ctx) { GroupProtocol.hello(st.identity, Pairing.derive(inv.secret).mailboxId, role, now()) }
        log(ctx, "group_joined")
        return Result.Ok()
    }

    fun inviteText(ctx: Context): String? {
        val st = GroupStore.load(ctx) ?: return null
        return GroupInvite.format(st.code, SyncStore.serverUrl(ctx))
    }

    fun view(ctx: Context): GroupProtocol.View? {
        val st = GroupStore.load(ctx) ?: return null
        return GroupProtocol.replay(Pairing.derive(Pairing.decode(st.code)!!).mailboxId, GroupStore.readLog(ctx))
    }

    /** I compared this member's fingerprint with theirs and trust them: pairings will be exchanged on the next pass. */
    fun approve(ctx: Context, memberId: String) {
        val st = GroupStore.load(ctx) ?: return
        GroupStore.save(ctx, st.copy(approved = st.approved + memberId))
        log(ctx, "group_approved")
        Sync.kick(ctx)
    }

    fun setRole(ctx: Context, role: Group.Role) = act(ctx, "group_role") { st, gid -> GroupProtocol.role(st.identity, gid, role, now()) }
    fun setViewers(ctx: Context, viewers: Set<String>?) = act(ctx, "group_viewers") { st, gid -> GroupProtocol.viewers(st.identity, gid, viewers, now()) }
    fun remove(ctx: Context, target: String) = act(ctx, "group_remove") { st, gid -> GroupProtocol.remove(st.identity, gid, target, now()) }
    fun close(ctx: Context) = act(ctx, "group_close") { st, gid -> GroupProtocol.close(st.identity, gid, now()) }

    /** Tells the group, then (once that is sent) lets go of every pairing made for it. */
    fun leave(ctx: Context) {
        val st = GroupStore.load(ctx) ?: return
        GroupStore.addOutbox(ctx, GroupProtocol.leave(st.identity, Pairing.derive(Pairing.decode(st.code)!!).mailboxId, now()))
        GroupStore.save(ctx, st.copy(ended = true))
        log(ctx, "group_left")
        Sync.kick(ctx)
    }

    private fun act(ctx: Context, event: String, build: (GroupStore.State, String) -> Map<String, Any?>) {
        val st = GroupStore.load(ctx) ?: return
        GroupStore.addOutbox(ctx, build(st, Pairing.derive(Pairing.decode(st.code)!!).mailboxId))
        log(ctx, event)
        Sync.kick(ctx)
    }

    private fun post(ctx: Context, build: () -> Map<String, Any?>) {
        GroupStore.addOutbox(ctx, build())
        Sync.schedule(ctx)
        Sync.kick(ctx)
    }

    private fun now() = System.currentTimeMillis()
    private fun log(ctx: Context, what: String) {
        EventLog.init(ctx)
        EventLog.activity(LogEvents.service(now(), "partner", what, null), urgent = true)
    }
}
