package app.focusfence.monitor

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import app.focusfence.data.EventLog
import app.focusfence.data.MonitorSettings
import app.focusfence.dns.B64
import app.focusfence.dns.CapturePolicy
import app.focusfence.dns.Classification
import app.focusfence.dns.Ecies
import app.focusfence.dns.LogEvents
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Takes sparse accountability screenshots of watched apps, classifies them on
 * device, and keeps only what the retention mode calls for — flagged images by
 * default, encrypted to the partner key so nothing readable is stored.
 *
 * Capture only ever happens when the owner has enabled it, only inside apps the
 * owner chose to watch, and only while the [CaptureIndicator] notification is
 * showing. `takeScreenshot` needs API 30+; on older devices screenshot capture
 * is simply unavailable (text capture still works).
 *
 * Timing is the pure [CapturePolicy]; all image work runs on a background
 * executor, never the main or accessibility thread.
 */
object ScreenCapture {

    private const val TAG = "FocusFenceCapture"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "focusfence-capture").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    @Volatile private var nextDueAt = 0L
    @Volatile private var lastForeground: String? = null

    @Volatile private var classifier: Classifier = NullClassifier
    @Volatile private var classifierLoadedFor: String? = null

    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Reload the classifier (model may have been added/removed). Call on settings change. */
    fun refresh(context: android.content.Context) {
        worker.execute {
            classifier.close()
            classifier = Classifiers.load(context)
            classifierLoadedFor = classifier.modelName
        }
    }

    /**
     * Called by the accessibility service when a *watched* app produces a window
     * event. Decides (via [CapturePolicy]) whether a capture is due and, if so,
     * requests one. Cheap and non-blocking on the calling thread.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun onWatchedWindow(service: AccessibilityService, pkg: String) {
        if (!MonitorSettings.screenshotsEnabled(service)) return
        val now = System.currentTimeMillis()
        if (pkg != lastForeground) {
            // New watched app in front: arm the next capture from now, so entering
            // an app doesn't instantly snapshot it.
            lastForeground = pkg
            nextDueAt = CapturePolicy.nextDueAt(now, MonitorSettings.level(service), Math.random())
            return
        }
        if (!CapturePolicy.shouldCapture(true, true, now, nextDueAt)) return
        if (!inFlight.compareAndSet(false, true)) return
        nextDueAt = CapturePolicy.nextDueAt(now, MonitorSettings.level(service), Math.random())
        requestScreenshot(service, pkg)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(service: AccessibilityService, pkg: String) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        process(service, pkg, result)
                    } finally {
                        inFlight.set(false)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Monitor.perf.count("shot_fail")
                    inFlight.set(false)
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "takeScreenshot", e)
            inFlight.set(false)
        }
    }

    /** Runs on the capture worker. */
    private fun process(context: android.content.Context, pkg: String, result: AccessibilityService.ScreenshotResult) {
        val bitmap = hardwareToSoftware(result) ?: run {
            Monitor.perf.count("shot_decode_fail")
            return
        }
        Monitor.perf.count("shot")

        ensureClassifier(context)
        // Battery: the AI is the expensive part of capture, so its volume and time are logged.
        val clfStart = android.os.SystemClock.elapsedRealtime()
        val scores = classifier.classify(bitmap)
        if (scores != null) {
            Monitor.perf.count("clf")
            Monitor.perf.count("clf_ms", android.os.SystemClock.elapsedRealtime() - clfStart)
        }
        val verdict = scores?.let { Classification.riskVerdict(it, MonitorSettings.threshold(context)) }
        val mode = MonitorSettings.retention(context)
        val flagged = verdict?.flagged ?: false
        val keep = Classification.retainImage(mode, flagged)

        var shotId: String? = null
        if (keep) {
            val key = partnerKey(context)
            if (key == null) {
                // Can't store an image we couldn't encrypt to the partner; keep the score only.
                Monitor.perf.count("shot_no_partner_key")
            } else {
                shotId = UUID.randomUUID().toString()
                try {
                    saveEncrypted(context, shotId, bitmap, key)
                } catch (e: Exception) {
                    Log.w(TAG, "encrypt/save shot", e)
                    Monitor.perf.count("shot_save_fail")
                    shotId = null
                }
            }
        }
        bitmap.recycle()

        EventLog.activity(
            LogEvents.shot(
                System.currentTimeMillis(), pkg, verdict?.score, verdict?.label,
                kept = shotId != null, shotId = shotId, model = classifier.modelName,
            )
        )
        if (flagged) Monitor.perf.count("shot_flagged")
    }

    private fun ensureClassifier(context: android.content.Context) {
        if (classifierLoadedFor == null && classifier is NullClassifier) {
            classifier = Classifiers.load(context)
            classifierLoadedFor = classifier.modelName ?: "none"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun hardwareToSoftware(result: AccessibilityService.ScreenshotResult): Bitmap? {
        var hardware: Bitmap? = null
        return try {
            val buffer: HardwareBuffer = result.hardwareBuffer
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            buffer.close()
            // TFLite needs a software bitmap; copy and drop the hardware one.
            hardware?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w(TAG, "wrap screenshot", e)
            null
        } finally {
            hardware?.recycle()
        }
    }

    private fun saveEncrypted(context: android.content.Context, shotId: String, bitmap: Bitmap, key: PublicKey) {
        // Downscale before storing: evidence doesn't need full resolution, and it
        // keeps ciphertext small. 640px long edge.
        val scaled = downscale(bitmap, 640)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        if (scaled != bitmap) scaled.recycle()
        val blob = Ecies.encrypt(jpeg, key)
        val dir = File(context.filesDir, "shots").apply { mkdirs() }
        File(dir, "$shotId.bin").writeBytes(blob)
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun partnerKey(context: android.content.Context): PublicKey? {
        val b64 = MonitorSettings.partnerKeySpki(context) ?: return null
        return try {
            Ecies.publicKeyFromSpki(B64.decode(b64))
        } catch (e: Exception) {
            null
        }
    }
}
