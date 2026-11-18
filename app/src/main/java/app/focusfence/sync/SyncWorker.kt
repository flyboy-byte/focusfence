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
        // Periodic and kicked runs can overlap; uploads must not interleave.
        synchronized(LOCK) {
            EventLog.init(ctx)
            runCatching { EventLog.flushBlocking() }
            val now = System.currentTimeMillis()
            val api = Api(SyncStore.serverUrl(ctx))
            var error: String? = null
            var heartbeat = false
            try {
                if (SyncStore.deviceId(ctx) != null) {
                    upload(ctx, api)
                    heartbeat = heartbeat(ctx, api, now)
                }
            } catch (e: Exception) {
                error = "server: ${e.javaClass.simpleName}: ${e.message}"
            }
            for (p in SyncStore.ownPairings(ctx)) {
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
        }
        // Failures are recorded and retried on the next period; WorkManager's
        // exponential backoff would only delay the next heartbeat further.
        return Result.success()
    }

    // ---- server path ---------------------------------------------------------

    private fun upload(ctx: Context, api: Api) {
        val deviceId = SyncStore.deviceId(ctx) ?: return
        repeat(MAX_BATCHES_PER_RUN) {
            val after = SyncStore.uploadedSeq(ctx)
            val lines = ArrayList<String>()
            var bytes = 0
            var first = -1L
            var last = -1L
            EventLog.scan(EventLog.Channel.ACTIVITY, afterSeq = after) { line ->
                val seq = EventLog.seqOf(line) ?: return@scan true
                if (seq <= after) return@scan true
                if (first < 0) first = seq
                last = seq
                lines += line
                bytes += line.length + 1
                bytes < MAX_BATCH_BYTES
            }
            if (lines.isEmpty()) return
            val raw = lines.joinToString("\n")
            val prev = SyncStore.lastHash(ctx)
            val sig = DeviceKey.sign("batch|$deviceId|$first|$last|$prev|$raw".toByteArray())
            val r = api.post("/v1/batches", JSONObject().apply {
                put("deviceId", deviceId)
                put("firstSeq", first)
                put("lastSeq", last)
                put("prevHash", prev)
                put("recordsRaw", raw)
                put("signature", sig)
            })
            when (r.code) {
                200 -> SyncStore.setCursor(ctx, last, r.body?.optString("nextHash") ?: return)
                409 -> {
                    // The server is ahead of us (a response got lost) or our cursor is
                    // stale: resume from its cursor. Nothing is hidden by this — the
                    // server keeps what it has and counts any gap itself.
                    val b = r.body ?: return
                    SyncStore.setCursor(ctx, maxOf(after, b.optLong("expectedNextSeq", 1) - 1), b.optString("lastHash", prev))
                }
                else -> throw IllegalStateException("upload HTTP ${r.code} ${r.body?.optString("error")}")
            }
        }
    }

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
            val r = api.post("/v1/mailbox/${k.mailboxId}", JSONObject().put("tokenHash", Pairing.tokenHash(k.token)))
            if (r.code != 200) throw IllegalStateException("mailbox HTTP ${r.code}")
            p = p.copy(boxReady = true)
            SyncStore.saveOwn(ctx, p)
        }

        // Instant alerts: everything Digest.alertText flags since the last scan.
        val alerts = ArrayList<String>()
        var maxSeq = p.alertSeq
        EventLog.scan(EventLog.Channel.ACTIVITY, afterSeq = p.alertSeq) { line ->
            val seq = EventLog.seqOf(line) ?: return@scan true
            if (seq <= p.alertSeq) return@scan true
            if (seq > maxSeq) maxSeq = seq
            // Cheap prefilter: only svc/integ lines can be alerts.
            if (line.contains("\"type\":\"integ\"") || line.contains("\"type\":\"svc\"")) {
                LogJson.decode(line)?.let(Digest::alertText)?.let { alerts += it }
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
        val k = w.keys
        val r = api.get("/v1/mailbox/${k.mailboxId}/msgs?after=${w.after}", mapOf("X-FF-Token" to k.token))
        when (r.code) {
            200 -> Unit
            404 -> return // their phone hasn't created the mailbox yet
            else -> throw IllegalStateException("mailbox read HTTP ${r.code}")
        }
        val msgs = r.body?.optJSONArray("msgs") ?: return
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
        if (!w.staleNotified && w.lastMsgMs > 0 && quietFor > STALE_AFTER_MS) {
            notify(ctx, w, ctx.getString(R.string.partner_stale_alert, quietFor / 3_600_000))
            w = w.copy(staleNotified = true)
        }
        SyncStore.saveWatch(ctx, w)
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
        private const val CHANNEL = "partner_alerts"
        private const val MAX_BATCHES_PER_RUN = 20
        private const val MAX_BATCH_BYTES = 256 * 1024
        const val DIGEST_EVERY_MS = 6 * 3_600_000L
        /** Two missed digests plus slack: Doze can hold work for a few hours. */
        const val STALE_AFTER_MS = 14 * 3_600_000L
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

    /** From screen-on: catch up if the periodic job has been held back (Doze). */
    fun kickIfDue(context: Context) {
        if (System.currentTimeMillis() - SyncStore.lastOk(context) > 20 * 60_000L) kick(context)
    }
}
