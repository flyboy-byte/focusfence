package app.focusfence.data

import android.content.Context
import android.graphics.Bitmap
import app.focusfence.dns.ReviewPolicy
import java.io.File

/**
 * Owner-review screenshots (see [ReviewPolicy]). Kept in `noBackupFilesDir` so
 * they are excluded from Android backups and device transfers, downscaled, and
 * pruned by age and count every time one is saved or the list is opened.
 */
object ReviewStore {

    private fun dir(c: Context) = File(c.noBackupFilesDir, "review").apply { mkdirs() }

    fun save(c: Context, bitmap: Bitmap, now: Long, pkg: String, label: String, score: Double) {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scale = if (longEdge > 720) 720f / longEdge else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val name = ReviewPolicy.fileName(ReviewPolicy.Entry(now, pkg, label, (score * 100).toInt()))
        try {
            File(dir(c), name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        prune(c, now)
    }

    fun prune(c: Context, now: Long = System.currentTimeMillis()) {
        val d = dir(c)
        ReviewPolicy.toPrune(d.list()?.toList() ?: emptyList(), now).forEach { File(d, it).delete() }
    }

    /** Newest first. */
    fun list(c: Context): List<Pair<ReviewPolicy.Entry, File>> {
        prune(c)
        val d = dir(c)
        return (d.list() ?: emptyArray()).mapNotNull { n -> ReviewPolicy.parse(n)?.let { it to File(d, n) } }
            .sortedByDescending { it.first.t }
    }

    fun count(c: Context): Int = list(c).size

    fun delete(c: Context, file: File) { file.delete() }

    fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }
}
