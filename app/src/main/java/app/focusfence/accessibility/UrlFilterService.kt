package app.focusfence.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.focusfence.R
import app.focusfence.BlockedActivity
import app.focusfence.BlockedNotice
import app.focusfence.data.BlockScreenSettings
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.EventLog
import app.focusfence.data.LogSettings
import app.focusfence.data.MonitorSettings
import app.focusfence.dns.BlockRule
import app.focusfence.dns.BlockScreen
import app.focusfence.dns.Blocklist
import app.focusfence.dns.LogEvents
import app.focusfence.dns.RuleEvaluator
import app.focusfence.dns.UrlDeduper
import app.focusfence.dns.UrlFilter
import app.focusfence.monitor.CaptureIndicator
import app.focusfence.monitor.Monitor
import app.focusfence.monitor.ScreenCapture
import app.focusfence.monitor.TextCapture
import java.time.LocalDate
import java.time.LocalTime

/**
 * Enforces "allow only specific sections" rules by watching browser address
 * bars. When the current URL falls under a section rule but outside its allowed
 * paths, the service presses Back. DNS cannot see URL paths (TLS encrypts
 * them), so this is the only self-contained way to block part of a website
 * while allowing another part.
 *
 * Reads only browser address bars. They are matched against the user's own
 * rules and, only if the owner turned on "Record browser addresses"
 * ([LogSettings.KEY_LOG_URLS]), written to the on-device activity log as
 * host + path (query strings and fragments are dropped). Nothing is
 * transmitted. Every enforcement action and the service's own lifecycle are
 * logged too: this service going away is a protection gap.
 */
class UrlFilterService : AccessibilityService() {

    companion object {
        /** Address-bar view IDs for common browsers. */
        private val URL_BAR_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.brave.browser_beta" to "com.brave.browser_beta:id/url_bar",
            "com.brave.browser_nightly" to "com.brave.browser_nightly:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "com.chrome.canary" to "com.chrome.canary:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        )

        private const val EVENT_THROTTLE_MS = 700L
        private const val BACK_DEBOUNCE_MS = 1500L
        private const val APP_BLOCK_DEBOUNCE_MS = 2000L
        /**
         * Text is read once when a screen opens and then at most this often while
         * it stays in front. It was 1.5 s on every content change: the
         * 2026-11-19 export showed 2,394 captures and ~41 s of CPU for it.
         */
        private const val TEXT_INTERVAL_MS = 30_000L
        /** Address bar re-read interval while a browser is in front (replaces content events). */
        private const val URL_POLL_MS = 4_000L
        /** Foreground tick when only text capture needs it. */
        private const val TEXT_POLL_MS = 15_000L
        private const val HIDDEN_SLOW_AFTER = 3

        @Volatile
        private var instance: UrlFilterService? = null

        /** From Monitor's screen-on broadcast: resume the foreground tick. */
        fun onScreenOn() {
            instance?.let { svc -> svc.main.post { svc.onForegroundTick() } }
        }

        /**
         * From the DNS filter: a front-door lookup for [domain] was just blocked
         * by a user rule. Returns false when this service isn't running, so the
         * caller can fall back to a notification. Costs nothing while idle: no
         * extra event types or packages, just one address-bar read on demand.
         */
        fun onDnsBlocked(domain: String, category: String?, tellMe: Boolean, tellPartner: Boolean): Boolean {
            val svc = instance ?: return false
            // The address bar can lag the lookup slightly; read it after a beat.
            svc.main.postDelayed({ svc.confirmAndShow(domain, category, tellMe, tellPartner, retry = true) }, CONFIRM_DELAY_MS)
            return true
        }

        private const val CONFIRM_DELAY_MS = 400L
        private const val CONFIRM_RETRY_MS = 1_200L
        private const val MAX_FALLBACK_NODES = 250
        private const val COMP = "a11y"
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var fgPkg: String? = null
    private val fgTick = Runnable { onForegroundTick() }

    @Volatile
    private var rules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var hasSectionRules = false

    @Volatile
    private var appRules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var logUrls = false

    @Volatile
    private var blockStyle = BlockScreen.Style.DEFAULT

    // Experimental monitoring: which apps to watch, and what to capture.
    @Volatile
    private var captureApps: Set<String> = emptySet()

    @Volatile
    private var watchMode = app.focusfence.dns.WatchScope.Mode.ALL

    @Volatile
    private var watchExcluded: Set<String> = emptySet()

    /** Home screen, system UI, keyboards, ourselves: never captured. Recomputed on reload. */
    @Volatile
    private var watchSkip: Set<String> = emptySet()

    /** Keyboard / status bar: windows over the app in front, not a switch of app. */
    @Volatile
    private var overlayPkgs: Set<String> = emptySet()

    @Volatile
    private var captureScreenshots = false

    @Volatile
    private var captureText = false

    private val urlDeduper = UrlDeduper()

    private var lastTextCaptureAt = 0L

    private var lastCheckAt = 0L
    private var lastBackAt = 0L
    private var lastAppBlockAt = 0L

    private var powerManager: PowerManager? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BlocklistStore.KEY_RULES || key == BlockedAppsStore.KEY_BLOCKED_APPS ||
            key == LogSettings.KEY_LOG_URLS || key == BlockScreenSettings.KEY_BLOCK_STYLE ||
            key == MonitorSettings.KEY_SCREENSHOTS || key == MonitorSettings.KEY_TEXT ||
            key == MonitorSettings.KEY_WATCHED || key == MonitorSettings.KEY_LEVEL ||
            key == MonitorSettings.KEY_AI || key == MonitorSettings.KEY_WATCH_MODE ||
            key == MonitorSettings.KEY_WATCH_EXCLUDED
        ) {
            reloadRules()
        }
    }

    override fun onServiceConnected() {
        // Held rather than fetched per event: onAccessibilityEvent is hot.
        powerManager = getSystemService(PowerManager::class.java)
        reloadRules()
        BlocklistStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Monitor.a11yConnected = true
        EventLog.activity(
            LogEvents.service(
                System.currentTimeMillis(), COMP, "connected",
                "section_rules=$hasSectionRules,blocked_apps=${appRules.size},log_urls=$logUrls",
            ),
            urgent = true,
        )
        Monitor.attach(this, COMP)
        instance = this
        ScreenCapture.refresh(this)
        CaptureIndicator.refresh(this)
    }

    override fun onDestroy() {
        main.removeCallbacks(fgTick)
        instance = null
        BlocklistStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Monitor.a11yConnected = false
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), COMP, "destroyed"), urgent = true)
        Monitor.detach(COMP)
        // Capture can't run without this service, so the indicator must not imply
        // it can; a later reconnect re-shows it.
        CaptureIndicator.clear(this)
        super.onDestroy()
    }

    private fun reloadRules() {
        val loaded = BlocklistStore.rulesByDomain(this)
        rules = loaded
        hasSectionRules = loaded.values.any { it.hasPathExceptions }
        appRules = BlockedAppsStore.rulesByPackage(this)
        logUrls = LogSettings.logUrls(this)
        blockStyle = BlockScreenSettings.style(this)
        captureApps = MonitorSettings.watchedApps(this)
        watchMode = MonitorSettings.watchMode(this)
        watchExcluded = MonitorSettings.excludedApps(this)
        watchSkip = app.focusfence.monitor.AppCatalog.alwaysSkip(this)
        overlayPkgs = app.focusfence.monitor.AppCatalog.overlays(this)
        captureScreenshots = MonitorSettings.screenshotsEnabled(this)
        captureText = MonitorSettings.textEnabled(this)
        applyEventFilter()
        ScreenCapture.refresh(this)
        // The indicator must reflect capture state exactly: show while capturing,
        // gone otherwise. Never hidden while capture is on (self-accountability).
        CaptureIndicator.refresh(this)
    }

    /** Everything time- and device-dependent a rule evaluation needs, sampled once. */
    private fun nowSnapshot(): RuleEvaluator.Now {
        val localTime = LocalTime.now()
        return RuleEvaluator.Now(
            minuteOfDay = localTime.hour * 60 + localTime.minute,
            epochMillis = System.currentTimeMillis(),
            dayKey = LocalDate.now().toString(),
            screenOn = powerManager?.isInteractive ?: true,
        )
    }

    /**
     * Scopes event delivery to only the packages we act on — the system then
     * never wakes this process for other apps' UI changes, which matters
     * enormously for battery. With nothing configured, listen only to our own
     * package (i.e. effectively nothing). Content-changed events (high volume)
     * are requested only while section rules exist or the owner opted into
     * recording browser addresses — that opt-in is the one place this service
     * widens, and the diag log measures what it costs (a11y_ev per window).
     */
    private fun applyEventFilter() {
        val info = serviceInfo ?: return
        val watchAll = watchMode == app.focusfence.dns.WatchScope.Mode.ALL
        val capturing = (captureScreenshots || captureText) && (watchAll || captureApps.isNotEmpty())
        val watchBrowsers = hasSectionRules || logUrls
        val targets = HashSet<String>()
        if (watchBrowsers) targets += URL_BAR_IDS.keys
        // Every package with an app rule, whether or not it blocks right now: the
        // service must still be receiving events for an app during its allowed
        // window, or it cannot act the moment that window closes.
        targets += appRules.keys
        if (capturing) targets += captureApps
        info.packageNames = when {
            // "All apps" capture is the one case that needs every package's events.
            // Its cost is measured, not assumed: diag a11y_ev per window.
            capturing && watchAll -> null
            targets.isEmpty() -> arrayOf(packageName)
            else -> targets.toTypedArray()
        }
        // Battery: content-changed events are the firehose (93k in 32 h on the
        // owner's phone, ~75% of the app's screen-on CPU across binder, main and
        // GC threads). Capture and URL recording now run on a foreground tick,
        // so only section rules — which must react instantly — still need them.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (hasSectionRules) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        // Batch event delivery: URL changes are human-speed, no need for more
        // than a couple of wakeups per second while scrolling.
        info.notificationTimeout = 600
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // CPU attribution: everything this service does per event (capture
        // decisions, URL reads, text walks) runs inside this call.
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        try {
            handleEvent(event)
        } finally {
            Monitor.perf.count("a11y_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        // Which app is in front drives the screenshot timer. Our own windows count
        // too (they're in front), just never watched.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage !in overlayPkgs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            app.focusfence.monitor.ScreenCapture.onForeground(
                this, eventPackage,
                captureScreenshots && app.focusfence.dns.WatchScope.watches(eventPackage, watchMode, captureApps, watchExcluded, watchSkip),
            )
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage !in overlayPkgs) {
            onForegroundChanged(eventPackage)
        }
        if (eventPackage == packageName) return
        Monitor.perf.count("a11y_ev")

        // Whole-app blocking: bounce to the home screen. Evaluated on window
        // state changes only — a window that closes while the user is already
        // inside the app does not eject them until the next window event. That
        // is deliberate: the impulse being blocked is *opening* the app, and a
        // timer to evict mid-session would mean a periodic wakeup.
        val appRule = appRules[eventPackage]
        if (appRule != null && RuleEvaluator.blocksApp(appRule, nowSnapshot())) {
            val now = SystemClock.uptimeMillis()
            if (now - lastAppBlockAt >= APP_BLOCK_DEBOUNCE_MS) {
                lastAppBlockAt = now
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, R.string.app_blocked_toast, Toast.LENGTH_SHORT).show()
                EventLog.activity(LogEvents.enforce(System.currentTimeMillis(), "app_home", eventPackage))
            }
            return
        }

        // Experimental capture: watched apps only, while the indicator is showing.
        if ((captureScreenshots || captureText) &&
            app.focusfence.dns.WatchScope.watches(eventPackage, watchMode, captureApps, watchExcluded, watchSkip)
        ) {
            maybeCapture(eventPackage)
        }

        // Section rules react to every event in a browser (they must be instant);
        // URL recording alone rides the foreground tick instead.
        if (hasSectionRules && eventPackage in URL_BAR_IDS) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCheckAt >= EVENT_THROTTLE_MS) {
                lastCheckAt = now
                checkUrl(eventPackage)
            }
        }
    }

    /** Reads the address bar: records it (if enabled) and enforces section rules. */
    private fun checkUrl(eventPackage: String) {
        val now = SystemClock.uptimeMillis()
        val url = findUrl(eventPackage)
        if (url == null && urlBarEditing) {
            // The user is typing in the address bar: the text is a half-typed
            // query, not a page (2.6.1 export logged "h10.56.148.43").
            Monitor.perf.count("url_editing")
            return
        }
        if (url == null) {
            if (urlBarHidden) {
                // Its known address bar isn't on screen: a video playing, the
                // toolbar scrolled away, a menu. Nothing is wrong (2026-11-26
                // export: 61% of "misses" were YouTube in Brave), so it's counted
                // apart and the poll slows down until the bar comes back.
                Monitor.perf.count("url_hidden")
                hiddenStreak++
                return
            }
            // A browser whose address bar was never found: it changed its view
            // IDs or isn't one we know. A high rate here is the signal to update
            // URL_BAR_IDS.
            Monitor.perf.count("url_miss")
            return
        }
        hiddenStreak = 0
        Monitor.perf.count("url_read")
        val parts = LogEvents.splitUrl(url)
        if (logUrls && parts != null && urlDeduper.accept(eventPackage, parts.first, parts.second)) {
            EventLog.activity(
                LogEvents.url(
                    System.currentTimeMillis(), eventPackage, parts.first, parts.second,
                    cat = app.focusfence.dns.Categories.match(parts.first, app.focusfence.vpn.DnsVpnService.detectionLists)
                        .takeIf { it.isNotEmpty() }?.joinToString(",")
                        ?.also { Monitor.perf.count("url_cat_hits") },
                )
            )
        }
        if (!hasSectionRules || !UrlFilter.shouldBlock(url, rules)) return

        if (now - lastBackAt >= BACK_DEBOUNCE_MS) {
            lastBackAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            // A toast, not a notification: the Back press already happened in front of the user.
            if (blockStyle != BlockScreen.Style.OFF) Toast.makeText(this, R.string.section_blocked_toast, Toast.LENGTH_SHORT).show()
            EventLog.activity(
                LogEvents.enforce(
                    System.currentTimeMillis(), "section_back", eventPackage,
                    parts?.first, parts?.second,
                )
            )
        }
    }

    /**
     * Announces the block (screen or notification, per [blockStyle]) only if
     * the app in front really is showing
     * [domain] in its address bar. A blocked lookup alone isn't enough: browsers
     * prefetch DNS for suggestions while the user types, and other apps look
     * up front-door hosts in the background. While the bar is being edited the
     * read returns null, which also keeps the screen from interrupting typing.
     */
    private fun confirmAndShow(domain: String, category: String?, tellMe: Boolean, tellPartner: Boolean, retry: Boolean) {
        val style = blockStyle
        val announce = tellMe && style != BlockScreen.Style.OFF
        if (!announce && !(tellPartner && category != null)) return
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (pkg == packageName) return
        val url = runCatching { findUrl(pkg) }.getOrNull()
        val parts = url?.let { LogEvents.splitUrl(it) }
        if (parts == null || !BlockScreen.hostMatches(parts.first, domain)) {
            if (retry) main.postDelayed({ confirmAndShow(domain, category, tellMe, tellPartner, retry = false) }, CONFIRM_RETRY_MS)
            return
        }
        if (announce) {
            val action = "block_notify"
            Monitor.perf.count(action)
            BlockedNotice.show(this, domain, category)
            EventLog.activity(
                LogEvents.enforce(System.currentTimeMillis(), action, pkg, parts.first, parts.second)
            )
        }
        if (tellPartner && category != null) BlockedNotice.onConfirmedHighRisk(this, domain, category)
    }

    /** Screenshot timing on window events (ScreenCapture also has its own timer). */
    private fun maybeCapture(pkg: String) {
        if (captureScreenshots && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ScreenCapture.onWatchedWindow(this, pkg)
        }
    }

    private fun tickNeeded(pkg: String): Boolean =
        pkg != packageName && (
            (logUrls && pkg in URL_BAR_IDS) ||
                (captureText && app.focusfence.dns.WatchScope.watches(pkg, watchMode, captureApps, watchExcluded, watchSkip))
            )

    /** A new app came to the front: read it once now, then keep a slow tick while it stays. */
    private fun onForegroundChanged(pkg: String) {
        if (pkg == fgPkg) return
        fgPkg = pkg
        lastTextCaptureAt = 0L // a new screen gets its text read right away
        hiddenStreak = 0 // and its address bar polled at the normal pace
        main.removeCallbacks(fgTick)
        if (tickNeeded(pkg)) onForegroundTick()
    }

    /**
     * One pass while an app stays in front with the screen on: address bar (if
     * recording URLs) and text (if watched, at most every [TEXT_INTERVAL_MS]).
     * Stops on screen off and when no longer needed; resumes on the next
     * window change or screen-on. Replaces per-content-change work.
     */
    private fun onForegroundTick() {
        main.removeCallbacks(fgTick)
        val pkg = fgPkg ?: return
        if (!tickNeeded(pkg)) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        // Window-state events can be missed; trust what's actually in front.
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (active != null && active != pkg && active !in overlayPkgs) {
            onForegroundChanged(active)
            return
        }
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        Monitor.perf.count("fg_tick")
        val isBrowser = pkg in URL_BAR_IDS
        if (logUrls && isBrowser) checkUrl(pkg)
        if (captureText && app.focusfence.dns.WatchScope.watches(pkg, watchMode, captureApps, watchExcluded, watchSkip)) {
            val now = SystemClock.uptimeMillis()
            if (lastTextCaptureAt == 0L || now - lastTextCaptureAt >= TEXT_INTERVAL_MS) {
                lastTextCaptureAt = now
                TextCapture.capture(pkg, rootInActiveWindow)
            }
        }
        Monitor.perf.count("a11y_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        // Three polls in a row with the address bar hidden (a video): poll at the
        // slower text pace until it shows again, instead of every 4 s for an hour.
        val fast = logUrls && isBrowser && hiddenStreak < HIDDEN_SLOW_AFTER
        main.postDelayed(fgTick, if (fast) URL_POLL_MS else TEXT_POLL_MS)
    }

    override fun onInterrupt() {
        EventLog.diag(LogEvents.error(System.currentTimeMillis(), COMP, "interrupt"))
    }

    /** Set by [findUrl] when the address bar has input focus (being edited). */
    private var urlBarEditing = false

    /** Set by [findUrl]: this browser's address bar ID has worked before, it just isn't on screen now. */
    private var urlBarHidden = false
    private var hiddenStreak = 0
    /** Browsers whose [URL_BAR_IDS] entry found the bar since the service started: their IDs are right. */
    private val idWorks = HashSet<String>()

    private fun findUrl(packageName: String): String? {
        urlBarEditing = false
        urlBarHidden = false
        val root = rootInActiveWindow ?: return null
        URL_BAR_IDS[packageName]?.let { id ->
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null) {
                idWorks += packageName
                if (node.isFocused) { urlBarEditing = true; return null }
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            } else if (packageName in idWorks) {
                // Known-good ID, bar not in the tree: hidden. The fallback search
                // would walk the whole page every poll and find nothing.
                urlBarHidden = true
                return null
            }
            Unit
        }
        return findUrlHeuristically(root)
    }

    /** Fallback for browsers not in the ID map: bounded BFS for an address-bar-like field. */
    private fun findUrlHeuristically(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_FALLBACK_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName.orEmpty()
            if (id.contains("url_bar") || id.contains("url_field") ||
                id.contains("location_bar") || id.contains("url_view")
            ) {
                if (node.isFocused) { urlBarEditing = true; return null }
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
