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
    /** Unchanged captures in a row in this app (CapturePolicy.backoff); written on main and the capture worker. */
    @Volatile private var sameStreak = 0
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
            sameStreak = 0
            // Arm from now, so entering an app doesn't instantly snapshot it.
            nextDueAt = CapturePolicy.nextDueAt(System.currentTimeMillis(), MonitorSettings.level(service), Math.random())
        }
        schedule()
    }

    /** The accessibility service is going away: stop the timer and let go of it, so a dead service isn't kept or used. */
    fun onServiceGone() {
        main.removeCallbacks(tick)
        foregroundWatched = false
        service = null
    }

    /** A watched app's window changed (scrolling, new content): capture now if due. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun onWatchedWindow(service: AccessibilityService, pkg: String) {
        if (pkg != lastForeground || !foregroundWatched) return
        if (!MonitorSettings.screenshotsEnabled(service)) return
        // A new window in the watched app is a new screen: drop any back-off and come back to the normal pace.
        if (sameStreak > 0) {
            sameStreak = 0
            nextDueAt = minOf(nextDueAt, System.currentTimeMillis() + MonitorSettings.level(service).minMs)
        }
        if (!CapturePolicy.shouldCapture(true, true, System.currentTimeMillis(), nextDueAt)) return
        capture(service, pkg, "event")
    }

    /**
     * Plan 17: adult words were just read on screen in [pkg]. Check the same screen with the image models now instead
     * of at the next random time, so a picture next to the words isn't scrolled away first. Only where screenshots
     * already run for that app, and not more than once per [TEXT_SHOT_GAP_MS].
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun onRiskyText(service: AccessibilityService, pkg: String) {
        if (pkg != lastForeground || !foregroundWatched) return
        if (!MonitorSettings.screenshotsEnabled(service)) return
        val now = System.currentTimeMillis()
        if (now - lastTextShotAt < TEXT_SHOT_GAP_MS) return
        lastTextShotAt = now
        Monitor.perf.count("shot_text")
        capture(service, pkg, "text")
    }
    private var lastTextShotAt = 0L
    private const val TEXT_SHOT_GAP_MS = 15_000L

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
        nextDueAt = CapturePolicy.nextDueAt(System.currentTimeMillis(), MonitorSettings.level(service), Math.random(), sameStreak)
        Monitor.perf.count("shot_req")
        val boxes = runCatching { pictureBoxes(service) }.getOrDefault(emptyList())
        requestScreenshot(service, pkg, trigger, boxes)
    }

    /**
     * Plan 17 piece 5: where the pictures and videos are on screen, from the accessibility tree, so each can be checked
     * on its own ([app.focusfence.dns.ImageRegions]). A bounded walk, like text capture's; on the main thread, before
     * the screenshot, so the bounds match what it shows.
     */
    private fun pictureBoxes(service: AccessibilityService): List<app.focusfence.dns.ImageRegions.Box> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<app.focusfence.dns.ImageRegions.Box>()
        val r = android.graphics.Rect()
        var visited = 0
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null || visited >= MAX_PICTURE_NODES || out.size >= 30) return
            visited++
            if (n.isVisibleToUser && app.focusfence.dns.ImageRegions.isPictureClass(n.className)) {
                n.getBoundsInScreen(r)
                out += app.focusfence.dns.ImageRegions.Box(r.left, r.top, r.right, r.bottom)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        Monitor.perf.count("region_nodes", visited.toLong())
        return out
    }
    private const val MAX_PICTURE_NODES = 400

    /** Which raw hits become partner flags; text capture feeds it adult-word sightings. */
    internal val flagPolicy = app.focusfence.dns.FlagPolicy()
    private val lastSuggestive = HashMap<String, Long>()
    private const val SUGGESTIVE_EVERY_MS = 10 * 60_000L

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
    private fun requestScreenshot(service: AccessibilityService, pkg: String, trigger: String, boxes: List<app.focusfence.dns.ImageRegions.Box>) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        process(service, pkg, result, trigger, boxes)
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
    @RequiresApi(Build.VERSION_CODES.R)
    private fun process(context: android.content.Context, pkg: String, result: AccessibilityService.ScreenshotResult, trigger: String, boxes: List<app.focusfence.dns.ImageRegions.Box>) {
        // CPU attribution: this whole pipeline (copy, scale, AI, encrypt) runs on
        // the capture worker thread, so thread CPU time is exactly its cost.
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        try {
            processInner(context, pkg, result, trigger, boxes)
        } finally {
            Monitor.perf.count("shot_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processInner(context: android.content.Context, pkg: String, result: AccessibilityService.ScreenshotResult, trigger: String, boxes: List<app.focusfence.dns.ImageRegions.Box>) {
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
            // Same screen again: wait longer before the next one (up to 4x), until something changes.
            sameStreak++
            nextDueAt = CapturePolicy.nextDueAt(now, MonitorSettings.level(context), Math.random(), sameStreak)
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
        val wholeVerdict = scores?.let { Classification.riskVerdict(it, MonitorSettings.threshold(context)) }
        // Plan 17 piece 5: each picture on screen (or three squares) on its own, so a small one is seen at full detail.
        val regions = if (scores != null) checkRegions(context, bitmap, boxes, wholeVerdict?.score ?: 0.0) else null
        val verdict = regions?.best?.takeIf { it.score > (wholeVerdict?.score ?: 0.0) } ?: wholeVerdict
        sameStreak = 0
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
        // D-038: a risky screenshot gets a second look from NudeNet, which finds exposed body parts instead of judging
        // the whole picture (faces and skin fooled the first model). A partner flag, and so an automatic send, needs it
        // to find one. Only if it can't run do the old FlagPolicy rules decide. The owner's review copy is not affected.
        val needBody = scores != null && (wholeVerdict?.flagged == true || (wholeVerdict?.score ?: 0.0) >= app.focusfence.dns.BodyCheck.CHECK_FROM)
        val wholeBody = if (needBody) {
            val b0 = android.os.SystemClock.elapsedRealtime()
            NudeDetector.detect(context, bitmap).also {
                Monitor.perf.count("body_runs")
                Monitor.perf.count("body_ms", android.os.SystemClock.elapsedRealtime() - b0)
            }
        } else null
        val body = app.focusfence.dns.ImageRegions.mergeBody(wholeBody, regions?.body)
        val bodyResult = body?.let(app.focusfence.dns.BodyCheck::classify)
        val confirmed = bodyResult?.takeIf { it.kind == app.focusfence.dns.BodyCheck.Kind.NUDITY }?.let { it.part!! to it.score }
        // Suggestive (lingerie, swimwear): captured, report-only for now. A line in the partner report, no alert, no picture.
        // One line per app per SUGGESTIVE_EVERY_MS: scrolling one page changes every capture and would fill the report.
        if (bodyResult?.kind == app.focusfence.dns.BodyCheck.Kind.SUGGESTIVE) {
            Monitor.perf.count("body_suggestive")
            if (now - (lastSuggestive[pkg] ?: 0L) >= SUGGESTIVE_EVERY_MS) {
                lastSuggestive[pkg] = now
                EventLog.activity(LogEvents.service(now, "monitor", "suggestive", app.focusfence.UiUtil.appLabel(context, pkg)))
            }
        }
        val why: String? = when {
            body != null -> if (confirmed != null) "body" else null
            verdict?.flagged == true -> flagPolicy.onRawHit(pkg, verdict.score, now)?.name?.lowercase()
            else -> null
        }
        val flagged = why != null
        // Plan 17: a risky picture makes text capture read this app deeper and more often for a while.
        if (flagged || (verdict?.score ?: 0.0) >= app.focusfence.dns.BodyCheck.CHECK_FROM ||
            bodyResult?.kind == app.focusfence.dns.BodyCheck.Kind.SUGGESTIVE) TextCapture.markHot(pkg, now)
        when {
            why != null -> Monitor.perf.count("flag_$why")
            verdict?.flagged == true -> Monitor.perf.count("flag_held")
        }
        val bodySummary = body?.let(app.focusfence.dns.BodyCheck::summary)
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
                app.focusfence.data.ReviewStore.save(context, bitmap, now, pkg, verdict.label, verdict.score,
                    app.focusfence.dns.Signals.scoresCompact(scores!!) + (body?.let { "," + app.focusfence.dns.BodyCheck.compact(it) } ?: ""))
                Monitor.perf.count("shot_review_kept")
            } catch (e: Exception) {
                Log.w(TAG, "save review shot", e)
                Monitor.perf.count("shot_review_fail")
            }
        }
        // D-037: a partner-level flag goes to partners on its own, blurred, after the owner's note window.
        if (flagged && verdict != null && app.focusfence.sync.SyncStore.deviceId(context) != null) {
            // The label partners see is what was found, when the body check found it.
            try {
                val thumb = app.focusfence.sync.ShareQueue.thumbnail(bitmap)
                val appName = app.focusfence.UiUtil.appLabel(context, pkg)
                val percent = (verdict.score * 100).toInt()
                val label = confirmed?.first?.lowercase()?.replace('_', ' ') ?: verdict.label
                val pct = confirmed?.let { Math.round(it.second * 100).toInt() } ?: percent
                val id = app.focusfence.sync.ShareQueue.enqueue(context, thumb, now, appName, label, pct, "", auto = true)
                AutoShareNotice.post(context, id, thumb, appName, label, pct, AutoShareNotice.sendAt(id.toLong()))
                Monitor.perf.count("shot_auto_queued")
            } catch (e: Exception) {
                Log.w(TAG, "auto share", e)
                Monitor.perf.count("shot_auto_fail")
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
                body = bodySummary,
                regions = regions?.summary,
            )
        )
        if (flagged) Monitor.perf.count("shot_flagged")
        app.focusfence.data.CaptureStats.recordShot(context, System.currentTimeMillis(), pkg, verdict?.label, verdict?.score, flagged)
    }

    private class RegionResult(val best: Classification.Verdict?, val body: Map<String, Double>?, val summary: String)

    /**
     * Runs the first model on each region from [app.focusfence.dns.ImageRegions.pick], and NudeNet on any that is
     * risky on its own (the same gate as the whole screen). Cost: one MobileNet run per region (~85 ms on a Pixel 9),
     * at most [app.focusfence.dns.ImageRegions.MAX]; counters `region_*`.
     */
    private fun checkRegions(context: android.content.Context, bitmap: Bitmap, boxes: List<app.focusfence.dns.ImageRegions.Box>, whole: Double): RegionResult? {
        val picked = app.focusfence.dns.ImageRegions.pick(bitmap.width, bitmap.height, boxes, whole)
        if (picked.isEmpty()) return null
        val threshold = MonitorSettings.threshold(context)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var best: Classification.Verdict? = null
        var bestAt = -1
        var body: Map<String, Double>? = null
        var bodyRuns = 0
        picked.forEachIndexed { i, b ->
            val crop = runCatching { Bitmap.createBitmap(bitmap, b.left, b.top, b.width, b.height) }.getOrNull() ?: return@forEachIndexed
            try {
                val v = classifier.classify(crop)?.let { Classification.riskVerdict(it, threshold) } ?: return@forEachIndexed
                Monitor.perf.count("region_runs")
                if (best == null || v.score > best!!.score) { best = v; bestAt = i }
                if (v.flagged || v.score >= app.focusfence.dns.BodyCheck.CHECK_FROM) {
                    bodyRuns++
                    Monitor.perf.count("region_body_runs")
                    body = app.focusfence.dns.ImageRegions.mergeBody(body, NudeDetector.detect(context, crop))
                }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        }
        Monitor.perf.count("region_ms", android.os.SystemClock.elapsedRealtime() - t0)
        val src = if (boxes.isNotEmpty() && picked != app.focusfence.dns.ImageRegions.tiles(bitmap.width, bitmap.height)) "pictures" else "tiles"
        Monitor.perf.count("region_src_$src")
        val b = best
        val summary = "n=${picked.size},src=$src" +
            (b?.let { ",best=${it.label}:${"%.3f".format(java.util.Locale.ROOT, it.score)}@$bestAt" } ?: "") +
            (if (bodyRuns > 0) ",body=$bodyRuns" else "")
        return RegionResult(b, body, summary)
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
