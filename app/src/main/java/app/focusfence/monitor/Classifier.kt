package app.focusfence.monitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * On-device image classifier for screenshots. Returns per-label scores (0..1).
 *
 * The model is **never bundled** — the owner drops a TFLite model and its label
 * list into the app's files dir (`classifier/model.tflite`, `classifier/labels.txt`),
 * for example a MobileNet NSFW classifier. With no model present, [load] returns
 * [NullClassifier]: capture still runs and records score-less shots, so the
 * pipeline is exercised, there is simply nothing to flag. This keeps Focus Fence
 * from redistributing a model (size and licensing) while leaving the capability
 * fully wired.
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
        val model = File(modelDir(context), "model.tflite")
        if (!model.exists()) return NullClassifier
        return try {
            TfliteClassifier(model, File(modelDir(context), "labels.txt"))
        } catch (e: Throwable) {
            Log.w(TAG, "classifier load failed, running without a model", e)
            NullClassifier
        }
    }
}
