package app.focusfence.monitor

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TensorFlow Lite image classifier. Expects a float model with input shape
 * [1, S, S, 3] (pixels scaled to 0..1) and output [1, N] of per-label scores;
 * [S] is read from the interpreter, and labels come from a newline-delimited
 * `labels.txt` (one per output index). This is the common MobileNet-style
 * export used by open NSFW detectors (e.g. Déchaîner's).
 *
 * All TFLite references are confined to this file so the rest of the app builds
 * and runs even if the native library is unavailable for an ABI; [Classifiers.load]
 * catches construction failures and falls back to [NullClassifier].
 */
class TfliteClassifier(modelFile: File, labelsFile: File) : Classifier {

    private val interpreter = Interpreter(modelFile)
    private val labels: List<String> =
        if (labelsFile.exists()) labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()
    private val inputSize: Int = interpreter.getInputTensor(0).shape().let { it[1] }
    override val modelName: String = modelFile.name

    override fun classify(bitmap: Bitmap): Map<String, Double> {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        if (scaled != bitmap) scaled.recycle()
        input.rewind()

        val outCount = interpreter.getOutputTensor(0).shape().let { it[it.size - 1] }
        val output = Array(1) { FloatArray(outCount) }
        interpreter.run(input, output)

        return output[0].mapIndexed { i, score ->
            (labels.getOrNull(i) ?: "label_$i") to score.toDouble()
        }.toMap()
    }

    override fun close() = interpreter.close()
}
