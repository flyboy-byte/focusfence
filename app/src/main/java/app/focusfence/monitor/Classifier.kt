package app.focusfence.monitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * On-device image classifier for screenshots. Returns per-label scores (0..1).
 *
 * A model ships in the APK (`assets/classifier/`: GantMan's MIT-licensed
 * MobileNet v2 NSFW model, float16, ~12 MB) and is used only when the owner
 * turns AI flagging on ([MonitorSettings.aiEnabled]). A model placed in the
 * app's files dir (`classifier/model.tflite` + `labels.txt`) overrides it.
 * With AI off, or if a model fails to load, [load] returns [NullClassifier]:
 * capture still runs and records score-less shots, so nothing breaks.
 */
interface Classifier {
    /** Label → probability, or null if no model is available. */
    fun classify(bitmap: Bitmap): Map<String, Double>?
    val modelName: String?
    fun close() {}
}

object NullClassifier : Classifier {
    override fun classify(bitmap: Bitmap): Map<String, Double>? = null
    override val modelName: String? = null
}

object Classifiers {
    private const val TAG = "FocusFenceClassifier"

    fun modelDir(context: Context) = File(context.filesDir, "classifier")

    /**
     * Builds a [TfliteClassifier] if a model file is present, else [NullClassifier].
     * Any failure to load (bad model, missing TFLite native lib on this ABI) is
     * caught and degrades to NullClassifier rather than breaking capture.
     */
    fun load(context: Context): Classifier {
        if (!app.focusfence.data.MonitorSettings.aiEnabled(context)) return NullClassifier
        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            val custom = File(modelDir(context), "model.tflite")
            val c = if (custom.exists()) {
                TfliteClassifier(custom, File(modelDir(context), "labels.txt"))
            } else {
                val (model, labels) = bundled(context)
                TfliteClassifier(model, labels)
            }
            Monitor.perf.count("clf_load_ms", android.os.SystemClock.elapsedRealtime() - started)
            c
        } catch (e: Throwable) {
            Log.w(TAG, "classifier load failed, running without a model", e)
            Monitor.perf.count("clf_load_fail")
            NullClassifier
        }
    }

    private const val BUNDLED = "nsfw_mobilenet_v2_f16.tflite"

    /** Copies the APK's model out once (TFLite needs a file); re-copies after an app update changes its size. */
    private fun bundled(context: Context): Pair<File, File> {
        val dir = File(context.noBackupFilesDir, "classifier_bundled").apply { mkdirs() }
        val model = File(dir, BUNDLED)
        val labels = File(dir, "labels.txt")
        val assetLen = context.assets.openFd("classifier/$BUNDLED").use { it.length }
        if (model.length() != assetLen) {
            val tmp = File(dir, "$BUNDLED.tmp")
            context.assets.open("classifier/$BUNDLED").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(model)
        }
        context.assets.open("classifier/labels.txt").use { i -> labels.outputStream().use { o -> i.copyTo(o) } }
        return model to labels
    }
}
