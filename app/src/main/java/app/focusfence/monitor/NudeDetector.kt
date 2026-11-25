package app.focusfence.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import app.focusfence.dns.BodyCheck
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NudeNet's body-part detector (D-038), bundled as `assets/classifier/nudenet_320n_f16.tflite`. Loaded on first use,
 * and only ever run on screenshots the first model already found risky ([BodyCheck.CHECK_FROM]), so it costs nothing
 * on ordinary screens. Preprocessing matches NudeNet's: pad to a square at the bottom/right with black, scale to
 * 320x320, RGB 0..1. A failure to load leaves [detect] returning null and the caller falls back to the old rules.
 */
object NudeDetector {
    private const val TAG = "FocusFenceNude"
    private const val ASSET = "nudenet_320n_f16.tflite"
    private const val SIZE = 320
    private const val ANCHORS = 2100

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var failed = false

    /** Best score per [BodyCheck.LABELS] label, or null if the model can't run. Call from the capture thread. */
    @Synchronized
    fun detect(context: Context, bitmap: Bitmap): Map<String, Double>? {
        val it = interpreter ?: load(context) ?: return null
        return try {
            val side = maxOf(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(square).apply {
                drawColor(Color.BLACK)
                val scale = SIZE.toFloat() / side
                drawBitmap(bitmap, null, Rect(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(SIZE * SIZE)
            square.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            square.recycle()
            val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            val out = Array(1) { Array(4 + BodyCheck.LABELS.size) { FloatArray(ANCHORS) } }
            it.run(input, out)
            val flat = FloatArray((4 + BodyCheck.LABELS.size) * ANCHORS)
            out[0].forEachIndexed { r, row -> System.arraycopy(row, 0, flat, r * ANCHORS, ANCHORS) }
            BodyCheck.best(flat, ANCHORS)
        } catch (e: Throwable) {
            Log.w(TAG, "body check failed", e)
            Monitor.perf.count("body_fail")
            null
        }
    }

    private fun load(context: Context): Interpreter? {
        if (failed) return null
        return try {
            val dir = File(context.noBackupFilesDir, "classifier_bundled").apply { mkdirs() }
            val model = File(dir, ASSET)
            val len = context.assets.openFd("classifier/$ASSET").use { it.length }
            if (model.length() != len) {
                val tmp = File(dir, "$ASSET.tmp")
                context.assets.open("classifier/$ASSET").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(model)
            }
            Interpreter(model).also { interpreter = it }
        } catch (e: Throwable) {
            Log.w(TAG, "body check model failed to load", e)
            Monitor.perf.count("body_load_fail")
            failed = true
            null
        }
    }
}
