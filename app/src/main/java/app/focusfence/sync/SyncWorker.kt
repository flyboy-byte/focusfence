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
import app.focusfence.dns.InviteLink
import app.focusfence.dns.B64
import app.focusfence.dns.Digest
import app.focusfence.dns.LogJson
import app.focusfence.dns.Pairing
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Everything that leaves the phone for accountability, in one periodic job
 * (WorkManager: batched with other apps' work, no wakeups of our own):
 *
 *  1. **Server path** (web/email/SMS partners): upload new activity-log lines as
 *     signed, hash-chained batches, then heartbeat. Silence is the server's
 *     signal that the app is gone.
 *  2. **App-to-app path**: for each partner watching this phone, post instant
 *     alerts and a periodic [Digest] to their blind mailbox, encrypted with the
 *     pairing key. For each phone this user watches, fetch and decrypt.
 *
 * Runs every 15 min (WorkManager's floor) and is kicked immediately when a
 * protection change is logged ([Sync.kick]).
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!SyncStore.hasWork(ctx)) return Result.success()
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        // Periodic and kicked runs can overlap; uploads must not interleave.
        synchronized(LOCK) {
            EventLog.init(ctx)
            runCatching { EventLog.flushBlocking() }
            val now = System.currentTimeMillis()
            val api = Api(SyncStore.serverUrl(ctx))
            var error: String? = null
            var heartbeat = false
            if (SyncStore.deviceId(ctx) != null) {
                // Independent steps: a server that predates D-023 (no /v1/reports) must not also stop the heartbeat,
                // or partners would get a false "phone went silent".
                for (step in listOf<() -> Unit>(
                    { heartbeat = heartbeat(ctx, api, now) },
                    // Shares before alerts: an automatic share logs its partner alert once uploaded, and that alert
                    // should go in this same pass.
                    { serverShares(ctx, api) },
                    { serverAlerts(ctx, api, now) },
                    { serverReport(ctx, api, now) },
                )) {
                    try { step() } catch (e: Exception) { error = error ?: "server: ${e.javaClass.simpleName}: ${e.message}" }
                }
            }
            // Groups first: a hand-over made now becomes a pairing the loops below can start using this same pass.
            if (app.focusfence.data.GroupStore.active(ctx)) {
                try {
                    GroupSync.run(ctx, api, now)
                } catch (e: Exception) {
                    error = error ?: "group: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            try {
                Accountability.resolvePending(ctx)
            } catch (e: Exception) {
                error = error ?: "invites: ${e.javaClass.simpleName}: ${e.message}"
            }
            for (p in SyncStore.ownPairings(ctx).filter { !it.pending }) {
                try {
                    sendToPartner(ctx, api, p, now)
                } catch (e: Exception) {
                    error = error ?: "pairing: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            for (w in SyncStore.watches(ctx)) {
                try {
                    receive(ctx, api, w, now)
                } catch (e: Exception) {
                    error = error ?: "watch: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            SyncStore.recordResult(ctx, now, error, heartbeat)
            app.focusfence.monitor.Monitor.perf.count("sync")
            if (error != null) app.focusfence.monitor.Monitor.perf.count("sync_fail")
            app.focusfence.monitor.Monitor.perf.count("sync_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
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
        for (f in ShareQueue.due(ctx, System.currentTimeMillis()).take(5)) {
            val now = System.currentTimeMillis()
            val blob = app.focusfence.dns.ReportSeal.seal(SyncStore.reportKey(ctx), f.readBytes(), deviceId)
            val r = api.post("/v1/reports", JSONObject().apply {
                put("deviceId", deviceId); put("t", now); put("from", now); put("to", now); put("blob", blob)
                put("signature", DeviceKey.sign("report|$deviceId|$now|$now|$now|${sha256(blob)}".toByteArray()))
            })
            if (r.code != 200) throw IllegalStateException("share HTTP ${r.code}")
            ShareQueue.sent(ctx, f)
        }
    }

    /** Same encoding as the server's `Integrity.sha256`: base64url, no padding. */
    private fun heartbeat(ctx: Context, api: Api, now: Long): Boolean {
        val deviceId = SyncStore.deviceId(ctx) ?: return false
        val seq = SyncStore.uploadedSeq(ctx)
        val r = api.post("/v1/heartbeat", JSONObject().apply {
            put("deviceId", deviceId)
            put("t", now)
            put("lastSeq", seq)
            put("signature", DeviceKey.sign("heartbeat|$deviceId|$now|$seq".toByteArray()))
        })
        if (r.code != 200) throw IllegalStateException("heartbeat HTTP ${r.code}")
        return true
    }

    // ---- app-to-app: this phone → its partners -------------------------------

    private fun sendToPartner(ctx: Context, api: Api, start: SyncStore.OwnPairing, now: Long) {
        var p = start
        val k = p.keys
        if (!p.boxReady) {
            val r = api.post("/v1/mailbox/${k.mailboxId}", Accountability.mailboxRequest(ctx, k.mailboxId, Pairing.tokenHash(k.token)))
            if (r.code != 200) throw IllegalStateException("mailbox HTTP ${r.code}")
            p = p.copy(boxReady = true)
            SyncStore.saveOwn(ctx, p)
        }

        // Instant alerts: what Digest.urgentText passes since the last scan (routine changes wait for the report).
        val alerts = ArrayList<String>()
        var maxSeq = p.alertSeq
        EventLog.scan(EventLog.Channel.ACTIVITY, afterSeq = p.alertSeq) { line ->
            val seq = EventLog.seqOf(line) ?: return@scan true
            if (seq <= p.alertSeq) return@scan true
            if (seq > maxSeq) maxSeq = seq
            // Cheap prefilter: only svc/integ lines can be alerts.
            if (line.contains("\"type\":\"integ\"") || line.contains("\"type\":\"svc\"")) {
                LogJson.decode(line)?.let(Digest::urgentText)?.let { alerts += it }
            }
            true
        }
        if (alerts.isNotEmpty()) {
            // Several at once (e.g. after being offline) go as one message.
            val text = if (alerts.size == 1) alerts[0] else alerts.takeLast(20).joinToString("\n")
            post(api, k, Digest.alert(now, text))
        }
        if (maxSeq != p.alertSeq) {
            p = p.copy(alertSeq = maxSeq)
            SyncStore.saveOwn(ctx, p)
        }

        if (now - p.lastDigestMs >= DIGEST_EVERY_MS) {
            val from = if (p.lastDigestMs > 0) p.lastDigestMs else p.createdMs
            val d = Digest(from)
            EventLog.scan(EventLog.Channel.ACTIVITY, sinceT = from) { line ->
                val t = LogJson.peekT(line)
                if (t != null && t >= from) LogJson.decode(line)?.let(d::add)
                true
            }
            post(api, k, d.build(now))
            SyncStore.saveOwn(ctx, p.copy(lastDigestMs = now))
        }
    }

    private fun post(api: Api, k: Pairing.Keys, message: Map<String, Any?>) {
        val sealed = Pairing.seal(k.enc, LogJson.encode(message).toByteArray(), k.mailboxId.toByteArray())
        val r = api.post("/v1/mailbox/${k.mailboxId}/msgs", JSONObject().put("c", B64.encode(sealed)), mapOf("X-FF-Token" to k.token))
        if (r.code != 200) throw IllegalStateException("mailbox post HTTP ${r.code}")
    }

    // ---- app-to-app: phones this user watches --------------------------------

    private fun receive(ctx: Context, api: Api, start: SyncStore.Watch, now: Long) {
        var w = start
        // D-035 partners read alerts from the server, like email partners: the same lines, and silence is decided
        // there (FF_STALE_MINUTES, about an hour) instead of here after 14 h. The mailbox still carries unlock codes.
        val feed = serverFeed(w, now)
        if (feed != null) {
            w = feed.first
            if (feed.second.isNotEmpty()) notify(ctx, w, feed.second.joinToString("\n"))
        }
        val k = w.keys
        val r = api.get("/v1/mailbox/${k.mailboxId}/msgs?after=${w.after}", mapOf("X-FF-Token" to k.token))
        when (r.code) {
            200 -> Unit
            404 -> { SyncStore.saveWatch(ctx, w); return } // their phone hasn't created the mailbox yet
            else -> { SyncStore.saveWatch(ctx, w); throw IllegalStateException("mailbox read HTTP ${r.code}") }
        }
        val msgs = r.body?.optJSONArray("msgs") ?: return SyncStore.saveWatch(ctx, w)
        val newAlerts = ArrayList<String>()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val id = m.optLong("id")
            val text = Pairing.open(k.enc, B64.decode(m.optString("c")), k.mailboxId.toByteArray())?.let { String(it) }
            val fields = text?.let(LogJson::decode)
            w = w.copy(after = maxOf(w.after, id), lastMsgMs = maxOf(w.lastMsgMs, m.optLong("t")), staleNotified = false)
            when (fields?.get("kind")) {
                Digest.KIND_DIGEST -> w = w.copy(digest = text)
                Digest.KIND_ALERT -> {
                    if (feed != null) continue // the server feed already carried it
                    val text = fields["text"] as? String ?: continue
                    newAlerts += text
                    val line = "${fields["t"] ?: m.optLong("t")}\t${text.replace('\n', ' ').replace('\t', ' ')}"
                    w = w.copy(alerts = (w.alerts.lines().filter { it.isNotBlank() } + line).takeLast(50).joinToString("\n"))
                }
            }
        }
        if (newAlerts.isNotEmpty()) notify(ctx, w, newAlerts.joinToString("\n"))
        // Their phone sends a digest every few hours; long silence is itself news.
        val quietFor = now - maxOf(w.lastMsgMs, w.addedMs)
        if (feed == null && !w.staleNotified && w.lastMsgMs > 0 && quietFor > STALE_AFTER_MS) {
            notify(ctx, w, ctx.getString(R.string.partner_stale_alert, quietFor / 3_600_000))
            w = w.copy(staleNotified = true)
        }
        SyncStore.saveWatch(ctx, w)
    }

    /**
     * New lines from this partner's server alert feed, and the watch with its cursor moved on. Null when there is no
     * report link or the server doesn't answer (an older server, or offline): the mailbox path then alerts as before.
     */
    private fun serverFeed(start: SyncStore.Watch, now: Long): Pair<SyncStore.Watch, List<String>>? {
        val (origin, path) = InviteLink.alertsTarget(start.reportUrl) ?: return null
        val r = runCatching { Api(origin).get("$path?after=${start.serverAlertT}") }.getOrNull() ?: return null
        val arr = r.body?.optJSONArray("alerts")
        if (r.code != 200 || arr == null) return null
        var w = start
        val lines = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val t = a.optLong("t")
            val text = a.optString("text").takeIf { it.isNotBlank() } ?: continue
            if (t <= w.serverAlertT) continue
            // The first read after an update can return two weeks of history: keep it, but only notify what's recent.
            if (start.serverAlertT > 0 || now - t < FIRST_FEED_NOTIFY_MS) lines += text
            val line = "$t\t${text.replace('\n', ' ').replace('\t', ' ')}"
            w = w.copy(serverAlertT = t, alerts = (w.alerts.lines().filter { it.isNotBlank() } + line).takeLast(50).joinToString("\n"))
        }
        return w to lines
    }

    private fun notify(ctx: Context, w: SyncStore.Watch, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.partner_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx, w.keys.mailboxId.hashCode(),
            Intent(ctx, PartnerReportActivity::class.java).putExtra(PartnerReportActivity.EXTRA_MAILBOX, w.keys.mailboxId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            w.keys.mailboxId.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.partner_alert_title, w.name))
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
            var maxSeq = after
            EventLog.scan(EventLog.Channel.ACTIVITY, afterSeq = after) { line ->
                val seq = EventLog.seqOf(line) ?: return@scan true
                if (seq <= after) return@scan true
                if (seq > maxSeq) maxSeq = seq
                if (line.contains("\"type\":\"integ\"") || line.contains("\"type\":\"svc\"")) {
                    LogJson.decode(line)?.let(Digest::urgentText)?.let { lines += it }
                }
                true
            }
            if (lines.isNotEmpty()) {
                val sent = lines.takeLast(20)
                val joined = sent.joinToString("\n")
                val r = api.post("/v1/alerts", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("t", now)
                    put("lines", org.json.JSONArray(sent))
                    put("signature", DeviceKey.sign("alerts|$deviceId|$now|${sha256(joined)}".toByteArray()))
                })
                if (r.code != 200) throw IllegalStateException("alerts HTTP ${r.code} ${r.body?.optString("error")}")
            }
            if (maxSeq != after) SyncStore.setCursor(ctx, maxSeq, SyncStore.lastHash(ctx))
        }

        internal fun sha256(s: String): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
        private const val CHANNEL = "partner_alerts"
        /** How long a sealed report slice stays open (D-023); it is re-sent at every sync until then. */
        const val REPORT_EVERY_MS = 3_600_000L
        const val DIGEST_EVERY_MS = 6 * 3_600_000L
        /** Two missed digests plus slack: Doze can hold work for a few hours. */
        const val STALE_AFTER_MS = 14 * 3_600_000L
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
