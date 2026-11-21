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

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var foregroundWatched = false
    private var service: AccessibilityService? = null

    /**
     * Captures run on a timer while a watched app is in front and the screen is
     * on — not only when the screen changes. A page sitting still sends no
     * accessibility events, so event-only triggering never captured a static
     * screen (seen on the owner's phone). The timer exists only in that state:
     * no watched app in front, or screen off, means nothing is scheduled.
     */
    private val tick = Runnable { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) onTimer() }

    /** Screen back on: resume the timer if a watched app is still in front. */
    fun onScreenOn() {
        if (foregroundWatched && service != null) main.post { schedule() }
    }

    /**
     * The accessibility service reports which app is in front (window-state
     * events, keyboard and status bar already filtered out) and whether capture
     * watches it.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun onForeground(service: AccessibilityService, pkg: String, watched: Boolean) {
        this.service = service
        val changed = pkg != lastForeground
        lastForeground = pkg
        foregroundWatched = watched && MonitorSettings.screenshotsEnabled(service)
        if (!foregroundWatched) {
            main.removeCallbacks(tick)
            return
        }
        if (changed) {
            // Arm from now, so entering an app doesn't instantly snapshot it.
            nextDueAt = CapturePolicy.nextDueAt(System.currentTimeMillis(), MonitorSettings.level(service), Math.random())
        }
        schedule()
    }

    /** A watched app's window changed (scrolling, new content): capture now if due. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun onWatchedWindow(service: AccessibilityService, pkg: String) {
        if (pkg != lastForeground || !foregroundWatched) return
        if (!MonitorSettings.screenshotsEnabled(service)) return
        if (!CapturePolicy.shouldCapture(true, true, System.currentTimeMillis(), nextDueAt)) return
        capture(service, pkg, "event")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onTimer() {
        val svc = service ?: return
        val pkg = lastForeground ?: return
        if (!foregroundWatched || !MonitorSettings.screenshotsEnabled(svc)) return
        val pm = svc.getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return // screen off: stop until the app is in front again
        // Window events may not reach us for every app (chosen-apps mode filters
        // them), so confirm what's actually in front before a timed capture.
        val active = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (active != null && active != pkg && active != "com.android.systemui") {
            foregroundWatched = false
            Monitor.perf.count("shot_timer_left_app")
            return
        }
        if (CapturePolicy.shouldCapture(true, true, System.currentTimeMillis(), nextDueAt)) {
            Monitor.perf.count("shot_timer")
            capture(svc, pkg, "timer")
        }
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        val delay = (nextDueAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        main.postDelayed(tick, delay)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture(service: AccessibilityService, pkg: String, trigger: String) {
        if (!inFlight.compareAndSet(false, true)) {
            Monitor.perf.count("shot_busy")
            return
        }
        nextDueAt = CapturePolicy.nextDueAt(System.currentTimeMillis(), MonitorSettings.level(service), Math.random())
        Monitor.perf.count("shot_req")
        requestScreenshot(service, pkg, trigger)
    }

    // Last scored screen, for skipping unchanged ones. Capture is serialized
    // (inFlight), so plain fields are safe here.
    private var lastHash: Long? = null
    private var lastHashPkg: String? = null
    private var lastHashAt = 0L
    private var lastLabel: String? = null
    private var lastScore: Double? = null

    private fun fingerprint(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, app.focusfence.dns.ImageHash.W, app.focusfence.dns.ImageHash.H, true)
        val px = IntArray(app.focusfence.dns.ImageHash.W * app.focusfence.dns.ImageHash.H)
        small.getPixels(px, 0, app.focusfence.dns.ImageHash.W, 0, 0, app.focusfence.dns.ImageHash.W, app.focusfence.dns.ImageHash.H)
        if (small !== bitmap) small.recycle()
        return app.focusfence.dns.ImageHash.dHash(IntArray(px.size) { app.focusfence.dns.ImageHash.luma(px[it]) })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(service: AccessibilityService, pkg: String, trigger: String) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        process(service, pkg, result, trigger)
                    } finally {
                        inFlight.set(false)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Codes: 1 internal, 2 no accessibility access (missing
                    // canTakeScreenshot), 3 interval too short, 4 invalid display,
                    // 5 invalid window, 6 secure window (app blocks screenshots).
                    Monitor.perf.count("shot_fail")
                    // Per-code counts: "secure window" (6) by app tells which apps block capture.
                    Monitor.perf.count("shot_fail_c$errorCode")
                    EventLog.diag(LogEvents.error(System.currentTimeMillis(), "capture", "shot_fail", "code=$errorCode pkg=$pkg"))
                    app.focusfence.data.CaptureStats.recordFailure(service)
                    inFlight.set(false)
                }
            })
        } catch (e: RuntimeException) {
            // A refusal can arrive as an exception instead of onFailure; it must
            // still show up as a failure, never as silence.
            Log.w(TAG, "takeScreenshot", e)
            Monitor.perf.count("shot_fail")
            EventLog.diag(LogEvents.error(System.currentTimeMillis(), "capture", "shot_exception", "${e.javaClass.simpleName}: ${e.message}"))
            app.focusfence.data.CaptureStats.recordFailure(service)
            inFlight.set(false)
        }
    }

    /** Runs on the capture worker. */
    private fun process(context: android.content.Context, pkg: String, result: AccessibilityService.ScreenshotResult, trigger: String) {
        // CPU attribution: this whole pipeline (copy, scale, AI, encrypt) runs on
        // the capture worker thread, so thread CPU time is exactly its cost.
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        try {
            processInner(context, pkg, result, trigger)
        } finally {
            Monitor.perf.count("shot_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    private fun processInner(context: android.content.Context, pkg: String, result: AccessibilityService.ScreenshotResult, trigger: String) {
        val bitmap = hardwareToSoftware(result) ?: run {
            Monitor.perf.count("shot_decode_fail")
            return
        }
        Monitor.perf.count("shot")

        // Same screen as last time (same app, recent)? Skip the model and reuse
        // its verdict: a 9x8 fingerprint is ~1000x cheaper than an inference.
        val hash = runCatching { fingerprint(bitmap) }.getOrNull()
        val now = System.currentTimeMillis()
        if (hash != null && app.focusfence.dns.ImageHash.unchanged(lastHash, lastHashPkg, lastHashAt, hash, pkg, now)) {
            Monitor.perf.count("shot_same")
            EventLog.activity(
                LogEvents.shot(now, pkg, lastScore, lastLabel, kept = false, shotId = null, model = classifier.modelName, trigger = trigger, same = true)
            )
            app.focusfence.data.CaptureStats.recordShot(context, now, pkg, lastLabel, lastScore, flagged = false)
            bitmap.recycle()
            return
        }

        ensureClassifier(context)
        // Battery: the AI is the expensive part of capture, so its volume and time are logged.
        val clfStart = android.os.SystemClock.elapsedRealtime()
        val clfCpu0 = android.os.Debug.threadCpuTimeNanos()
        val scores = classifier.classify(bitmap)
        val clfMs = android.os.SystemClock.elapsedRealtime() - clfStart
        val verdict = scores?.let { Classification.riskVerdict(it, MonitorSettings.threshold(context)) }
        if (scores != null && hash != null) {
            lastHash = hash
            lastHashPkg = pkg
            lastHashAt = now
            lastLabel = verdict?.label
            lastScore = verdict?.score
        }
        if (scores != null) {
            Monitor.perf.count("clf")
            Monitor.perf.count("clf_ms", clfMs)
            Monitor.perf.count("clf_cpu_us", (android.os.Debug.threadCpuTimeNanos() - clfCpu0) / 1000)
            // Detection tuning: which label wins, and how risky scores are spread.
            scores.maxByOrNull { it.value }?.let { Monitor.perf.count("clf_top_${it.key}") }
            verdict?.let { Monitor.perf.count("clf_risk_b${app.focusfence.dns.Signals.bucket(it.score)}") }
        }
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
        // Owner-review copy: a lower, owner-chosen bar than the partner flag, local only.
        if (verdict != null && app.focusfence.dns.ReviewPolicy.shouldKeep(
                MonitorSettings.reviewEnabled(context), verdict.score, MonitorSettings.reviewPercent(context))
        ) {
            try {
                app.focusfence.data.ReviewStore.save(context, bitmap, now, pkg, verdict.label, verdict.score, app.focusfence.dns.Signals.scoresCompact(scores!!))
                Monitor.perf.count("shot_review_kept")
            } catch (e: Exception) {
                Log.w(TAG, "save review shot", e)
                Monitor.perf.count("shot_review_fail")
            }
        }
        bitmap.recycle()

        EventLog.activity(
            LogEvents.shot(
                System.currentTimeMillis(), pkg, verdict?.score, verdict?.label,
                kept = shotId != null, shotId = shotId, model = classifier.modelName,
                scores = scores?.let(app.focusfence.dns.Signals::scoresCompact),
                flagged = flagged,
                ms = if (scores != null) clfMs else null,
                trigger = trigger,
            )
        )
        if (flagged) Monitor.perf.count("shot_flagged")
        app.focusfence.data.CaptureStats.recordShot(context, System.currentTimeMillis(), pkg, verdict?.label, verdict?.score, flagged)
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
