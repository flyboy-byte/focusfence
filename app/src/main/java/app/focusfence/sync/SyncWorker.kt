package app.focusfence.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.focusfence.PartnerReportActivity
import app.focusfence.R
import app.focusfence.data.EventLog
import app.focusfence.dns.AlertPrefs
import app.focusfence.dns.LogEvents
import app.focusfence.dns.B64
import app.focusfence.dns.Digest
import app.focusfence.dns.LogJson
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Everything that leaves the phone for accountability, in one periodic job
 * (WorkManager: batched with other apps' work, no wakeups of our own):
 *
 *  1. **This phone, watched**: heartbeat (and seal the report key to partner devices that need it), alert lines,
 *     the sealed report. Silence is the server's signal that the app is gone.
 *  2. **Reports this user sees** (plan 13): for each, this device's copy of the report key until it has the current
 *     one, and the alert feed, notified by this partner's own choices.
 *
 * Runs every 15 min (WorkManager's floor) and is kicked immediately when a
 * protection change is logged ([Sync.kick]).
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!SyncStore.hasWork(ctx)) return Result.success()
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        val wall0 = android.os.SystemClock.elapsedRealtime()
        // Periodic and kicked runs can overlap; uploads must not interleave.
        synchronized(LOCK) {
            EventLog.init(ctx)
            runCatching { EventLog.flushBlocking() }
            val now = System.currentTimeMillis()
            // A phone that joined someone's report before 3.0.1 isn't on the server itself yet (two kinds of phone: on a
            // server or not). Daily at most, so a refusal doesn't knock on every sync.
            if (SyncStore.deviceId(ctx) == null && now - SyncStore.partnerEnrollTriedMs(ctx) > 86_400_000) {
                SyncStore.setPartnerEnrollTriedMs(ctx, now)
                runCatching { Accountability.enrollAsPartner(ctx) }
            }
            val api = Api(SyncStore.serverUrl(ctx))
            var error: String? = null
            var heartbeat = false
            if (SyncStore.deviceId(ctx) != null) {
                // Independent steps: a server that predates D-023 (no /v1/reports) must not also stop the heartbeat,
                // or partners would get a false "phone went silent".
                for ((name, step) in listOf<Pair<String, () -> Unit>>(
                    "heartbeat" to { heartbeat = heartbeat(ctx, api, now) },
                    // Shares before alerts: an automatic share logs its partner alert once uploaded, and that alert
                    // should go in this same pass.
                    "shares" to { serverShares(ctx, api) },
                    "alerts" to { serverAlerts(ctx, api, now) },
                    "report" to { serverReport(ctx, api, now) },
                )) {
                    try { step() } catch (e: Exception) { error = error ?: "server: ${e.javaClass.simpleName}: ${e.message}"; logStepError(name, e) }
                }
            }
            for (seen in SyncStore.seen(ctx)) {
                try {
                    partnerSide(ctx, seen, now)
                } catch (e: Exception) {
                    error = error ?: "seen: ${e.javaClass.simpleName}: ${e.message}"
                    logStepError("seen", e)
                }
            }
            SyncStore.recordResult(ctx, now, error, heartbeat)
            app.focusfence.monitor.Monitor.perf.count("sync")
            if (error != null) app.focusfence.monitor.Monitor.perf.count("sync_fail")
            app.focusfence.monitor.Monitor.perf.count("sync_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
            app.focusfence.monitor.Monitor.perf.count("sync_ms", android.os.SystemClock.elapsedRealtime() - wall0)
            notificationState(ctx)
        }
        // Failures are recorded and retried on the next period; WorkManager's
        // exponential backoff would only delay the next heartbeat further.
        return Result.success()
    }

    // ---- server path ---------------------------------------------------------

    /**
     * D-023: the partner's report is a [Digest] sealed with this phone's report key ([ReportSeal]); the server stores
     * ciphertext and the partner's browser opens it. Hourly slices, merged by the report page, so each run reads at
     * most an hour of log (the first one reads up to a week).
     */
    private fun serverReport(ctx: Context, api: Api, now: Long) {
        val deviceId = SyncStore.deviceId(ctx) ?: return
        // Delivery (2026-11-25): the open slice (from the last closed one to now) goes up at every sync, about every
        // 15 min, and the server replaces the previous upload of it (same `from`). It closes after REPORT_EVERY_MS, so
        // partners see data minutes old while the server still keeps one slice an hour.
        val last = SyncStore.lastReportMs(ctx)
        val from = if (last > 0) last else now - 7 * 24 * 3_600_000L
        val d = Digest(from)
        EventLog.scan(EventLog.Channel.ACTIVITY, sinceT = from) { line ->
            val t = LogJson.peekT(line)
            if (t != null && t >= from) LogJson.decode(line)?.let(d::add)
            true
        }
        val blob = app.focusfence.dns.ReportSeal.seal(SyncStore.reportKey(ctx), LogJson.encode(d.build(now)).toByteArray(), deviceId)
        val r = api.post("/v1/reports", JSONObject().apply {
            put("deviceId", deviceId)
            put("t", now)
            put("from", from)
            put("to", now)
            put("blob", blob)
            put("signature", DeviceKey.sign("report|$deviceId|$now|$from|$now|${sha256(blob)}".toByteArray()))
        })
        if (r.code != 200) throw IllegalStateException("report HTTP ${r.code} ${r.body?.optString("error")}")
        if (last <= 0 || now - last >= REPORT_EVERY_MS) SyncStore.setLastReportMs(ctx, now)
    }

    /** Owner-chosen blurred screenshots (Phase 2 level B), each sealed like a report and sent as its own slice. */
    private fun serverShares(ctx: Context, api: Api) {
        val deviceId = SyncStore.deviceId(ctx) ?: return
        for (due in ShareQueue.due(ctx, System.currentTimeMillis()).take(5)) {
            val f = ShareQueue.claim(ctx, due)
            val now = System.currentTimeMillis()
            val r = try {
                val blob = app.focusfence.dns.ReportSeal.seal(SyncStore.reportKey(ctx), f.readBytes(), deviceId)
                api.post("/v1/reports", JSONObject().apply {
                    put("deviceId", deviceId); put("t", now); put("from", now); put("to", now); put("blob", blob)
                    put("signature", DeviceKey.sign("report|$deviceId|$now|$now|$now|${sha256(blob)}".toByteArray()))
                })
            } catch (e: Exception) {
                ShareQueue.release(ctx, f)
                throw e
            }
            if (r.code != 200) { ShareQueue.release(ctx, f); throw IllegalStateException("share HTTP ${r.code}") }
            ShareQueue.sent(ctx, f)
        }
    }

    /**
     * A failed sync step goes into the diag log (it used to reach only Settings' "last error"), once per step until its
     * message changes or six hours pass: a phone offline all night writes a line, not one per sync.
     */
    private fun logStepError(step: String, e: Exception) {
        val what = "${e.javaClass.simpleName}: ${e.message}".take(200)
        val now = System.currentTimeMillis()
        val last = lastStepError[step]
        if (last != null && last.first == what && now - last.second < 6 * 3_600_000L) return
        lastStepError[step] = what to now
        EventLog.diag(LogEvents.error(now, "sync", step, what))
    }

    /**
     * Whether partner notifications can show at all, logged when it changes: an alert "that never came" is often a
     * category the person switched off in Android's settings.
     */
    private fun notificationState(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val parts = listOf(CHANNEL, CH_OTHER, CH_HELD, CH_YOURS).map { id -> "$id=${nm.getNotificationChannel(id)?.importance ?: "none"}" }
        val state = "enabled=${nm.areNotificationsEnabled()}," + parts.joinToString(",")
        val p = ctx.getSharedPreferences("focusfence_sync_diag", Context.MODE_PRIVATE)
        if (p.getString("notify_state", null) == state) return
        p.edit().putString("notify_state", state).apply()
        EventLog.diag(LogEvents.service(System.currentTimeMillis(), "notify", "channels", state))
    }

    /** Same encoding as the server's `Integrity.sha256`: base64url, no padding. */
    private fun heartbeat(ctx: Context, api: Api, now: Long): Boolean {
        val deviceId = SyncStore.deviceId(ctx) ?: return false
        val seq = SyncStore.uploadedSeq(ctx)
        val gap = gapAccount(ctx, now)
        val r = api.post("/v1/heartbeat", JSONObject().apply {
            put("deviceId", deviceId)
            put("t", now)
            put("lastSeq", seq)
            put("signature", DeviceKey.sign("heartbeat|$deviceId|$now|$seq".toByteArray()))
            put("noticesAfter", SyncStore.ownerNoticeT(ctx))
            put("keyId", app.focusfence.dns.ReportSeal.keyId(SyncStore.reportKey(ctx)))
            // After a long gap, the phone's own account of it for partners ("no internet; protection stayed on").
            if (gap != null) {
                put("gap", gap)
                put("gapSignature", DeviceKey.sign("gap|$deviceId|$now|${sha256(gap)}".toByteArray()))
            }
        })
        if (r.code == 404 && r.body?.optString("error") == "unknown device") {
            // The server no longer knows this phone (reset, or the operator removed it). Forget the enrollment so the
            // Partners screen opens the Server card to reconnect, instead of failing here at every sync.
            SyncStore.clearEnrollment(ctx)
            EventLog.activity(LogEvents.service(now, "partner", "server_forgot", null), urgent = true)
            throw IllegalStateException(ctx.getString(R.string.acc_server_forgot))
        }
        if (r.code != 200) throw IllegalStateException("heartbeat HTTP ${r.code}")
        ownerNotices(ctx, r.body?.optJSONArray("notices"))
        unlockAnswer(ctx, r.body?.optJSONObject("unlock"), now)
        if (r.body?.optBoolean("rotateKey") == true) {
            // A partner left or dropped a device: a new key now, sealed to everyone left at the next check-in (at once).
            SyncStore.rotateReportKey(ctx)
            EventLog.activity(LogEvents.service(now, "partner", "key_rotated", null), urgent = true)
            Sync.kick(ctx)
        } else sealKeys(ctx, api, r.body?.optJSONArray("seal"), now)
        groupPairs(ctx, api, r.body?.optJSONArray("groupPairs"), now)
        // While an invite is open, check in every minute for 30 minutes after making it, so a joiner waits under one.
        // A group being joined does the same for every member, so the keys go round within minutes.
        if (r.body?.optBoolean("groupsBusy") == true ||
            (r.body?.optInt("invitesOpen") ?: 0) > 0 && now - SyncStore.inviteMadeMs(ctx) < INVITE_FAST_MS) {
            app.focusfence.monitor.Monitor.perf.count("invite_poll")
            Sync.kickAfter(ctx, "invite_${now / 60_000}", 60_000)
        }
        return true
    }

    /**
     * Plan 13: partner devices waiting for this phone's report key. Each gets it sealed to its own public key, so the
     * server relays only ciphertext. A partner's first device means they just joined; a later one, that they added a
     * phone or browser. Both are logged and shown: who can read this phone's report is never a silent change.
     */
    private fun sealKeys(ctx: Context, api: Api, arr: org.json.JSONArray?, now: Long) {
        if (arr == null || arr.length() == 0) return
        val deviceId = SyncStore.deviceId(ctx) ?: return
        val key = SyncStore.reportKey(ctx)
        val keyId = app.focusfence.dns.ReportSeal.keyId(key)
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val pdev = t.optString("deviceId").takeIf { it.isNotEmpty() } ?: continue
            val pub = runCatching { app.focusfence.dns.Ecies.publicKeyFromSpki(B64.decode(t.optString("publicKeySpki"))) }.getOrNull()
                ?: continue.also { EventLog.diag(LogEvents.error(now, "seal", "bad_key", pdev.take(8))) }
            val sealed = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(app.focusfence.dns.Ecies.encrypt(key.toByteArray(), pub, app.focusfence.dns.Ecies.INFO_KEY))
            val r = api.post("/v1/seal", JSONObject().apply {
                put("deviceId", deviceId); put("t", now); put("partnerDeviceId", pdev); put("keyId", keyId); put("sealed", sealed)
                put("signature", DeviceKey.sign("seal|$deviceId|$now|$pdev|$keyId|${sha256(sealed)}".toByteArray()))
            })
            if (r.code != 200) { EventLog.diag(LogEvents.error(now, "seal", "http_${r.code}", pdev.take(8))); continue }
            app.focusfence.monitor.Monitor.perf.count("sealed")
            val name = t.optString("name").take(40).ifEmpty { "A partner" }
            val label = t.optString("label").take(40)
            val known = resealing(ctx, pdev)
            if (known) continue
            val group = t.optString("groupName")
            if (t.optBoolean("firstDevice") && group.isNotEmpty()) {
                // The group's own notice ("Sam joined Tuesday group") already told the person; this records the key going over.
                EventLog.activity(LogEvents.service(now, "partner", "partner_joined", "$name ($group)"), urgent = true)
            } else if (t.optBoolean("firstDevice")) {
                EventLog.activity(LogEvents.service(now, "partner", "partner_joined", name), urgent = true)
                notifyOwner(ctx, now + i, ctx.getString(R.string.owner_joined_title, name), ctx.getString(R.string.owner_joined_text, name))
            } else {
                EventLog.activity(LogEvents.service(now, "partner", "device_added", "$name: $label"), urgent = true)
                notifyOwner(ctx, now + i, ctx.getString(R.string.owner_device_title, name), ctx.getString(R.string.owner_device_text, name, label))
            }
        }
    }

    /**
     * Plan 14: reports a group lets this phone read. For each, this phone makes a key of its own and sends the public
     * half; that member's phone seals its report key to it, exactly as for a partner who joined by invite.
     */
    private fun groupPairs(ctx: Context, api: Api, arr: org.json.JSONArray?, now: Long) {
        if (arr == null || arr.length() == 0) return
        val deviceId = SyncStore.deviceId(ctx) ?: return
        val label = android.os.Build.MODEL?.take(40).orEmpty().ifEmpty { "Phone" }
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val owner = g.optString("ownerDeviceId"); val pid = g.optString("partnerId"); val token = g.optString("token")
            if (owner.isEmpty() || pid.isEmpty() || token.isEmpty()) continue
            val key = PartnerKey.create()
            val r = api.post("/v1/groups/key", JSONObject().apply {
                put("deviceId", deviceId); put("t", now); put("ownerDeviceId", owner); put("partnerId", pid)
                put("publicKeySpki", key.publicSpkiB64); put("label", label)
                put("signature", DeviceKey.sign("group_key|$deviceId|$now|$owner|$pid|${sha256(key.publicSpkiB64)}".toByteArray()))
            })
            val pdev = r.body?.optString("deviceId").orEmpty()
            if (r.code != 200 || pdev.isEmpty()) { EventLog.diag(LogEvents.error(now, "group", "key_http_${r.code}", pid.take(8))); continue }
            val name = g.optString("name").take(40).ifEmpty { "A member" }
            val group = g.optString("groupName").take(40)
            SyncStore.saveSeen(ctx, SyncStore.Seen(id = pid, server = SyncStore.serverUrl(ctx).trimEnd('/'), token = token, deviceId = pdev,
                wrappedKey = key.wrapped, owner = name, addedMs = now, group = group))
            EventLog.activity(LogEvents.service(now, "partner", "group_pair", "$name ($group)"), urgent = true)
        }
    }

    /**
     * Plan 12 §3: the owner's ask-a-partner request as the server has it. A fresh answer is logged and notified; while
     * it's still waiting, check again in a minute (at most 15 minutes, then it lapses).
     */
    private fun unlockAnswer(ctx: Context, o: org.json.JSONObject?, now: Long) {
        if (o == null) return
        val a = app.focusfence.data.ApprovalStore.Ask(o.optString("id"), o.optString("action"), o.optString("state"),
            o.optString("by").take(40), o.optLong("askedMs"), o.optLong("decidedMs"))
        if (a.id.isEmpty()) return
        val before = app.focusfence.data.ApprovalStore.save(ctx, a)
        val action = app.focusfence.dns.GuardPolicy.Action.fromKey(a.action) ?: return
        val fresh = before == null || before.id != a.id || before.state != a.state
        if (fresh && (a.state == app.focusfence.dns.UnlockAsk.APPROVED || a.state == app.focusfence.dns.UnlockAsk.DENIED)) {
            val approved = a.state == app.focusfence.dns.UnlockAsk.APPROVED
            EventLog.activity(LogEvents.service(now, "strict", if (approved) "partner_approved" else "partner_denied",
                "${action.name.lowercase()} by=${a.by}"), urgent = true)
            notifyOwner(ctx, a.decidedMs, ctx.getString(R.string.ask_answer_title), app.focusfence.dns.UnlockAsk.answerLine(a.by, approved, action))
        }
        if (a.state == app.focusfence.dns.UnlockAsk.PENDING) {
            app.focusfence.monitor.Monitor.perf.count("ask_poll") // the extra once-a-minute syncs while an ask waits
            Sync.kickAfter(ctx, "unlock_${now / 60_000}", 60_000)
        }
    }

    /** Plan 12: what the server says the owner should know (a partner left). Logged, and one notification each. */
    private fun ownerNotices(ctx: Context, arr: org.json.JSONArray?) {
        if (arr == null) return
        var last = SyncStore.ownerNoticeT(ctx)
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val t = n.optLong("t")
            val text = n.optString("text").takeIf { it.isNotBlank() }?.take(300) ?: continue
            if (t <= last) continue
            val kind = n.optString("kind")
            val state = when (kind) { "device" -> "device_removed"; "group_joined" -> "group_member_joined"; "group_left" -> "group_member_left"; else -> "partner_left" }
            EventLog.activity(LogEvents.service(t, "partner", state, text), urgent = true)
            notifyOwner(ctx, t, ctx.getString(R.string.owner_notice_title), text)
            last = t
        }
        SyncStore.setOwnerNoticeT(ctx, last)
    }

    private fun notifyOwner(ctx: Context, t: Long, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(ctx, nm)
        val open = PendingIntent.getActivity(
            ctx, 0x0e1f, Intent(ctx, app.focusfence.AccountabilityActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            (t % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(ctx, CH_YOURS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** [GapAccount] for the time since the last check-in that got through, if that was long ago. */
    private fun gapAccount(ctx: Context, now: Long): String? {
        val from = SyncStore.lastHeartbeat(ctx)
        if (from <= 0 || now - from < app.focusfence.dns.GapAccount.MIN_GAP_MS) return null
        return runCatching {
            val g = app.focusfence.dns.GapAccount(from)
            EventLog.scan(EventLog.Channel.ACTIVITY, sinceT = from) { line ->
                if (line.contains("\"type\":\"svc\"") || line.contains("\"type\":\"integ\"")) LogJson.decode(line)?.let(g::addActivity)
                else g.addActivity(mapOf("t" to LogJson.peekT(line), "seq" to EventLog.seqOf(line))) // sequence only, no decode
                true
            }
            EventLog.scan(EventLog.Channel.DIAG, sinceT = from) { line ->
                if (line.contains("\"type\":\"net\"")) LogJson.decode(line)?.let(g::addNet)
                true
            }
            g.describe(now)
        }.getOrNull()
    }

    // ---- reports this user sees ----------------------------------------------

    /**
     * A device sealed once is sealed again after every key change: only a device never sealed before is news. Kept as
     * the set of partner-device ids this phone has ever sealed to.
     */
    private fun resealing(ctx: Context, pdev: String): Boolean {
        val p = ctx.getSharedPreferences("focusfence_sync_diag", Context.MODE_PRIVATE)
        val known = p.getStringSet("sealed_devices", emptySet()).orEmpty()
        if (pdev in known) return true
        p.edit().putStringSet("sealed_devices", (known + pdev).toList().takeLast(50).toSet()).apply()
        return false
    }

    private fun partnerSide(ctx: Context, start: SyncStore.Seen, now: Long) {
        var s = start
        val api = Api(s.server)
        // This device's copy of the report key: until it arrives (the owner's phone seals it at its next check-in), and
        // whenever the owner starts a new one.
        val k = runCatching { api.get("/p/${s.token}/key?d=${s.deviceId}") }.getOrNull()
        when {
            k == null -> Unit
            // A group pair goes when someone leaves the group, and the group said so: forget it rather than show it as gone.
            k.code == 404 && s.group.isNotEmpty() -> { SyncStore.removeSeen(ctx, s); EventLog.diag(LogEvents.service(now, "partner", "group_pair_gone", s.owner.take(40))); return }
            k.code == 404 -> if (!s.linkGone) { s = s.copy(linkGone = true); EventLog.diag(LogEvents.service(now, "partner", "link_gone", s.owner.take(40))) }
            k.code == 200 && k.body?.optBoolean("ready") == true && k.body.optString("keyId") != s.keyId -> {
                val key = runCatching { PartnerKey.open(s.wrappedKey, k.body.optString("sealed")) }
                    .onFailure { EventLog.diag(LogEvents.error(now, "seen", "open_key", it.javaClass.simpleName)) }.getOrNull()
                if (key != null) {
                    if (s.reportKey.isEmpty()) {
                        EventLog.activity(LogEvents.service(now, "partner", "report_ready", s.owner.take(40)), urgent = true)
                        notify(ctx, s, ctx.getString(R.string.seen_ready, s.owner), CH_OTHER)
                    }
                    s = s.copy(reportKey = key, keyId = k.body.optString("keyId"), linkGone = false)
                }
            }
            k.code == 200 && s.reportKey.isEmpty() && now - s.addedMs < INVITE_FAST_MS -> {
                app.focusfence.monitor.Monitor.perf.count("key_poll")
                Sync.kickAfter(ctx, "key_${now / 60_000}", 60_000)
            }
        }
        if (!s.linkGone && s.reportKey.isNotEmpty()) {
            val feed = serverFeed(s, now)
            if (feed?.gone == true) s = s.copy(linkGone = true)
            else if (feed != null) {
                s = feed.seen
                // Plan 12: important and housekeeping alerts in their own notification categories, so each can be set apart.
                if (feed.important.isNotEmpty()) notify(ctx, s, feed.important.joinToString("\n"))
                if (feed.other.isNotEmpty()) notify(ctx, s, feed.other.joinToString("\n"), CH_OTHER)
                s = flushHeld(ctx, s, now, feed.prefs)
            }
        }
        if (s != start) SyncStore.saveSeen(ctx, s)
    }

    /** One read of a report's alert feed: the report moved on, what to notify now (by category), and their choices. */
    private class Feed(val seen: SyncStore.Seen, val important: List<String>, val other: List<String>, val prefs: AlertPrefs.Prefs, val gone: Boolean = false)

    /** New lines from the feed (/p/{token}/alerts), or null when the server doesn't answer (offline): tried next time. */
    private fun serverFeed(start: SyncStore.Seen, now: Long): Feed? {
        val perf = app.focusfence.monitor.Monitor.perf
        perf.count("feed_read")
        // `d` tells the server this device's app reads the feed, so this partner's alerts stop going by email too.
        val r = runCatching { Api(start.server).get("/p/${start.token}/alerts?after=${start.serverAlertT}&d=${start.deviceId}") }.getOrNull()
        val arr = r?.body?.optJSONArray("alerts")
        if (r?.code == 404) { perf.count("feed_gone"); EventLog.diag(LogEvents.service(now, "partner", "link_gone", start.owner.take(40))); return Feed(start, emptyList(), emptyList(), AlertPrefs.Prefs(), gone = true) }
        if (r == null || r.code != 200 || arr == null) { perf.count("feed_fail"); return null }
        var w = start
        val lines = ArrayList<String>()
        // Plan 12 §5: this partner's own choices, in this phone's time zone (absent from an older server: everything).
        val prefs = feedPrefs(r.body)
        val hour = AlertPrefs.hour(prefs, now)
        logFeedPrefs(start, prefs)
        val other = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val t = a.optLong("t")
            val text = a.optString("text").takeIf { it.isNotBlank() } ?: continue
            if (t <= w.serverAlertT) continue
            // The first read after an update can return two weeks of history: keep it, but only notify what's recent.
            perf.count("feed_new")
            // Why a partner's phone did or didn't show an alert: counted by route (feed_now / _other / _held / _skip).
            if (start.serverAlertT > 0 || now - t < FIRST_FEED_NOTIFY_MS) when (AlertPrefs.route(prefs, a.optBoolean("important", true), hour).also {
                perf.count(when (it) { AlertPrefs.Route.NOW -> if (a.optBoolean("important", true)) "feed_now" else "feed_other"; AlertPrefs.Route.HOLD -> "feed_held"; AlertPrefs.Route.SKIP -> "feed_skip" })
            }) {
                AlertPrefs.Route.NOW -> if (a.optBoolean("important", true)) lines += text else other += text
                AlertPrefs.Route.HOLD -> w = w.copy(held = (w.held.lines().filter { it.isNotBlank() } + "$t\t${text.replace('\n', ' ').replace('\t', ' ')}").takeLast(50).joinToString("\n"))
                AlertPrefs.Route.SKIP -> Unit
            }
            val line = "$t\t${text.replace('\n', ' ').replace('\t', ' ')}"
            w = w.copy(serverAlertT = t, alerts = (w.alerts.lines().filter { it.isNotBlank() } + line).takeLast(50).joinToString("\n"))
        }
        return Feed(w, lines, other, prefs)
    }

    private fun feedPrefs(body: org.json.JSONObject?) = AlertPrefs.Prefs(
        AlertPrefs.Level.fromKey(body?.optString("level")), body?.optBoolean("quiet") == true, java.time.ZoneId.systemDefault().id,
        AlertPrefs.hourOr(body?.optInt("quietFrom", -1), AlertPrefs.QUIET_FROM_HOUR), AlertPrefs.hourOr(body?.optInt("quietTo", -1), AlertPrefs.QUIET_TO_HOUR),
    )

    /** A partner's own alert choices, logged when they change, so "I didn't get it" can be checked against them. */
    private fun logFeedPrefs(w: SyncStore.Seen, p: AlertPrefs.Prefs) {
        val ctx = applicationContext
        val v = "level=${p.level.key},quiet=${if (p.quiet) "${p.quietFrom}-${p.quietTo}" else "off"}"
        val sp = ctx.getSharedPreferences("focusfence_sync_diag", Context.MODE_PRIVATE)
        val key = "feed_prefs_" + w.id.take(16)
        if (sp.getString(key, null) == v) return
        sp.edit().putString(key, v).apply()
        EventLog.diag(LogEvents.service(System.currentTimeMillis(), "partner", "feed_prefs", "${w.owner.take(40)}: $v"))
    }

    /** Plan 12 §5: held alerts go out as one notification at 7 this phone's time, never inside quiet hours. */
    private fun flushHeld(ctx: Context, w: SyncStore.Seen, now: Long, prefs: AlertPrefs.Prefs): SyncStore.Seen {
        val held = w.held.lines().filter { it.isNotBlank() }
        if (held.isEmpty()) return w
        val day = AlertPrefs.day(prefs, now)
        if (!AlertPrefs.flushDue(prefs, AlertPrefs.hour(prefs, now), day, w.heldDay, true)) return w
        app.focusfence.monitor.Monitor.perf.count("feed_flush")
        notify(ctx, w, ctx.getString(R.string.partner_held_title, held.size) + "\n" + held.joinToString("\n") { it.substringAfter('\t') }, CH_HELD)
        return w.copy(held = "", heldDay = day)
    }

    /**
     * Partner notifications come in four categories, so people can give each its own sound and Do Not Disturb
     * behaviour in Android's settings. [CHANNEL] keeps its old id, so settings made for "Partner alerts" carry over.
     */
    private fun channels(ctx: Context, nm: NotificationManager) {
        fun ch(id: String, name: Int, desc: Int, importance: Int) =
            NotificationChannel(id, ctx.getString(name), importance).apply { description = ctx.getString(desc) }
        nm.createNotificationChannels(listOf(
            ch(CHANNEL, R.string.partner_channel_name, R.string.partner_channel_desc, NotificationManager.IMPORTANCE_HIGH),
            ch(CH_OTHER, R.string.partner_other_channel, R.string.partner_other_channel_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_HELD, R.string.partner_held_channel, R.string.partner_held_channel_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_YOURS, R.string.partner_yours_channel, R.string.partner_yours_channel_desc, NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    private fun notify(ctx: Context, w: SyncStore.Seen, text: String, channel: String = CHANNEL) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(ctx, nm)
        val open = PendingIntent.getActivity(
            ctx, w.id.hashCode(),
            Intent(ctx, PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_SEEN, w.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            // One notification per partner per category: an "other" one never replaces an important one.
            w.id.hashCode() + listOf(CHANNEL, CH_OTHER, CH_HELD).indexOf(channel).coerceAtLeast(0),
            NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.partner_alert_title, w.owner))
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private val LOCK = Any()

        /**
         * Pushes pending alert lines to the server now, on the caller's thread. For uninstall protection: Android's
         * "Deactivate & uninstall" removes the app seconds after admin is turned off, so waiting for the next sync
         * would mean partners only ever learn from the "phone went silent" alert hours later.
         */
        fun pushAlertsNow(ctx: Context) {
            if (SyncStore.deviceId(ctx) == null) return
            synchronized(LOCK) {
                EventLog.init(ctx)
                runCatching { EventLog.flushBlocking() }
                runCatching { serverAlerts(ctx, Api(SyncStore.serverUrl(ctx)), System.currentTimeMillis()) }
            }
        }

        /**
         * D-023: the server never receives activity records. Protection changes go up as their alert text only (the same
         * [Digest.alertText] lines app partners get: "Filter turned off", never a site), and the server emails them.
         */
        internal fun serverAlerts(ctx: Context, api: Api, now: Long) {
            val deviceId = SyncStore.deviceId(ctx) ?: return
            val after = SyncStore.uploadedSeq(ctx)
            val lines = ArrayList<String>()
            val important = ArrayList<Boolean>()
            var maxSeq = after
            EventLog.scan(EventLog.Channel.ACTIVITY, afterSeq = after) { line ->
                val seq = EventLog.seqOf(line) ?: return@scan true
                if (seq <= after) return@scan true
                if (seq > maxSeq) maxSeq = seq
                if (line.contains("\"type\":\"integ\"") || line.contains("\"type\":\"svc\"")) {
                    LogJson.decode(line)?.let { f -> Digest.urgentText(f)?.let { lines += it; important += AlertPrefs.isImportant(f) } }
                }
                true
            }
            if (lines.isNotEmpty()) {
                val sent = lines.takeLast(20)
                val sentImportant = important.takeLast(20)
                val joined = sent.joinToString("\n")
                val r = api.post("/v1/alerts", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("t", now)
                    put("lines", org.json.JSONArray(sent))
                    // Plan 12 §5: housekeeping lines, so partners who chose fewer alerts get them later or not at all.
                    put("minor", org.json.JSONArray(sent.indices.filter { !sentImportant[it] }))
                    put("signature", DeviceKey.sign("alerts|$deviceId|$now|${sha256(joined)}".toByteArray()))
                })
                if (r.code != 200) throw IllegalStateException("alerts HTTP ${r.code} ${r.body?.optString("error")}")
            }
            if (maxSeq != after) SyncStore.setCursor(ctx, maxSeq, SyncStore.lastHash(ctx))
        }

        internal fun sha256(s: String): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
        private val lastStepError = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        private const val CHANNEL = "partner_alerts"
        private const val CH_OTHER = "partner_other"
        private const val CH_HELD = "partner_held"
        private const val CH_YOURS = "partner_yours"
        /** How long a sealed report slice stays open (D-023); it is re-sent at every sync until then. */
        const val REPORT_EVERY_MS = 3_600_000L
        /** Plan 13: how long after an invite (or a join) the phone checks every minute, so nobody waits long for the key. */
        const val INVITE_FAST_MS = 30 * 60_000L
        private const val FIRST_FEED_NOTIFY_MS = 2 * 3_600_000L
    }
}

/** Scheduling for [SyncWorker]. */
object Sync {
    private const val PERIODIC = "focusfence_sync"
    private const val NOW = "focusfence_sync_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!SyncStore.hasWork(context)) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    /** Run soon (protection changed, or the user just set something up). */
    fun kick(context: Context) {
        if (!SyncStore.hasWork(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
        )
    }

    /** A run at [delayMs] from now, for a queued automatic share (D-037); one per share so none waits for another. */
    fun kickAfter(context: Context, id: String, delayMs: Long) {
        if (!SyncStore.hasWork(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "focusfence_share_$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build(),
        )
    }

    /** From screen-on: catch up if the periodic job has been held back (Doze). */
    fun kickIfDue(context: Context) {
        if (System.currentTimeMillis() - SyncStore.lastOk(context) > 20 * 60_000L) kick(context)
    }
}
