package app.focusfence.sync

import android.content.Context
import android.graphics.Bitmap
import app.focusfence.data.EventLog
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

    fun enqueue(ctx: Context, t: ShotBlur.Thumb, shotT: Long, app: String, label: String, percent: Int, note: String) {
        val now = System.currentTimeMillis()
        val env = JSONObject().apply {
            put("kind", "shot"); put("v", 1); put("t", now); put("shot_t", shotT)
            put("app", app); put("label", label); put("pct", percent); put("note", note.take(200))
            put("w", t.w); put("h", t.h)
            put("rgb", java.util.Base64.getEncoder().encodeToString(t.rgb))
        }
        File(dir(ctx), "$now.json").writeText(env.toString())
        EventLog.activity(LogEvents.service(now, "partner", "shot_shared", "$label $percent%"), urgent = true)
        Sync.kick(ctx)
    }

    fun pending(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
}
