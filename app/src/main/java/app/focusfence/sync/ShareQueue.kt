package app.focusfence.sync

import android.content.Context
import android.graphics.Bitmap
import app.focusfence.data.EventLog
import app.focusfence.dns.AutoShare
import app.focusfence.dns.LogEvents
import app.focusfence.dns.ShotBlur
import org.json.JSONObject
import java.io.File

/**
 * Screenshots the owner chose to share (Phase 2, level B). Only the [ShotBlur] thumbnail is ever written here or
 * sent, never the original; [SyncWorker] seals each one with the report key and posts it as its own report slice, so
 * the server holds ciphertext only (D-023) and the partner's browser decrypts it next to the report.
 */
object ShareQueue {
    private fun dir(ctx: Context) = File(ctx.filesDir, "shares").apply { mkdirs() }

    fun thumbnail(bitmap: Bitmap): ShotBlur.Thumb {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ShotBlur.thumbnail(px, bitmap.width, bitmap.height)
    }

    /** A bitmap of the thumbnail, scaled up with smoothing, exactly what the partner will see. */
    fun preview(t: ShotBlur.Thumb, width: Int): Bitmap {
        val px = IntArray(t.w * t.h) { i ->
            (0xff shl 24) or ((t.rgb[i * 3].toInt() and 0xff) shl 16) or ((t.rgb[i * 3 + 1].toInt() and 0xff) shl 8) or (t.rgb[i * 3 + 2].toInt() and 0xff)
        }
        val small = Bitmap.createBitmap(px, t.w, t.h, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, width, maxOf(1, width * t.h / t.w), true)
    }

    /**
     * Queues a thumbnail and returns its id. [auto] (D-037): a flagged shot nobody chose to send. It waits
     * [AutoShare.DELAY_MS] so the owner can add a note, is labelled "sent automatically" for the partner, and its
     * partner alert is logged when it actually goes ([sent]), so the alert never arrives before the picture.
     */
    fun enqueue(ctx: Context, t: ShotBlur.Thumb, shotT: Long, app: String, label: String, percent: Int, note: String, auto: Boolean = false): String {
        val now = System.currentTimeMillis()
        val sendAt = AutoShare.sendAt(now, auto)
        val env = JSONObject().apply {
            put("kind", "shot"); put("v", 1); put("t", now); put("shot_t", shotT)
            put("app", app); put("label", label); put("pct", percent); put("note", note.take(200))
            put("w", t.w); put("h", t.h)
            put("rgb", java.util.Base64.getEncoder().encodeToString(t.rgb))
            if (auto) { put("auto", true); put("send_at", sendAt) }
        }
        val id = now.toString()
        File(dir(ctx), "$id.json").writeText(env.toString())
        if (auto) {
            EventLog.activity(LogEvents.service(now, "partner", "shot_auto_queued", "$label $percent%"), urgent = true)
            Sync.kickAfter(ctx, id, AutoShare.DELAY_MS)
        } else {
            EventLog.activity(LogEvents.service(now, "partner", "shot_shared", "$label $percent%"), urgent = true)
            Sync.kick(ctx)
        }
        return id
    }

    /** Adds the owner's note to a queued automatic share. False if it has already gone. */
    fun addNote(ctx: Context, id: String, note: String): Boolean = synchronized(this) {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return false
        val env = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return false
        env.put("note", note.take(200))
        val tmp = File(dir(ctx), "$id.tmp")
        tmp.writeText(env.toString())
        tmp.renameTo(f)
    }

    /** Called after an upload: an automatic share's partner alert goes now that the picture is there. */
    fun sent(ctx: Context, f: File) {
        val env = runCatching { JSONObject(f.readText()) }.getOrNull()
        synchronized(this) { f.delete() }
        if (env?.optBoolean("auto") == true) {
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), "partner", "shot_auto_sent",
                AutoShare.sentText(env.optString("label"), env.optInt("pct"))), urgent = true)
        }
    }

    /**
     * Marks a due share as being uploaded, so a note arriving mid-upload is refused ("already sent") rather than
     * accepted and lost. A file left `.sending` by a crash is picked up again by [due]; only one sync runs at a time.
     */
    fun claim(ctx: Context, f: File): File = synchronized(this) {
        if (f.name.endsWith(".sending")) return f
        val to = File(dir(ctx), f.name.removeSuffix(".json") + ".sending")
        if (f.renameTo(to)) to else f
    }

    /** An upload failed: put it back so notes can still be added until the next try. */
    fun release(ctx: Context, f: File) = synchronized(this) {
        if (f.name.endsWith(".sending")) f.renameTo(File(dir(ctx), f.name.removeSuffix(".sending") + ".json"))
    }

    fun pending(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".sending") }.orEmpty().sortedBy { it.name }

    /** The queued shares whose time has come (manual ones at once, automatic ones after the delay). */
    fun due(ctx: Context, now: Long): List<File> = pending(ctx).filter { f ->
        val sendAt = runCatching { JSONObject(f.readText()).optLong("send_at") }.getOrDefault(0)
        AutoShare.isDue(sendAt, now)
    }
}
