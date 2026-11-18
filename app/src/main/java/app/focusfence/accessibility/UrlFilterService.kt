package app.focusfence.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.focusfence.R
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.EventLog
import app.focusfence.data.LogSettings
import app.focusfence.data.MonitorSettings
import app.focusfence.dns.BlockRule
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UrlDeduper
import app.focusfence.dns.UrlFilter
import app.focusfence.admin.AppSuspension
import app.focusfence.monitor.CaptureIndicator
import app.focusfence.monitor.Monitor
import app.focusfence.monitor.ScreenCapture
import app.focusfence.monitor.TextCapture

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
        private const val TEXT_CAPTURE_THROTTLE_MS = 1500L
        private const val MAX_FALLBACK_NODES = 250
        private const val COMP = "a11y"
    }

    @Volatile
    private var rules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var hasSectionRules = false

    @Volatile
    private var blockedApps: Set<String> = emptySet()

    @Volatile
    private var logUrls = false

    // Experimental monitoring: which apps to watch, and what to capture.
    @Volatile
    private var captureApps: Set<String> = emptySet()

    @Volatile
    private var captureScreenshots = false

    @Volatile
    private var captureText = false

    private val urlDeduper = UrlDeduper()

    private var lastTextCaptureAt = 0L

    private var lastCheckAt = 0L
    private var lastBackAt = 0L
    private var lastAppBlockAt = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BlocklistStore.KEY_RULES || key == BlockedAppsStore.KEY_BLOCKED_APPS ||
            key == LogSettings.KEY_LOG_URLS ||
            key == MonitorSettings.KEY_SCREENSHOTS || key == MonitorSettings.KEY_TEXT ||
            key == MonitorSettings.KEY_WATCHED || key == MonitorSettings.KEY_LEVEL ||
            key == MonitorSettings.KEY_AI
        ) {
            reloadRules()
        }
    }

    override fun onServiceConnected() {
        reloadRules()
        BlocklistStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Monitor.a11yConnected = true
        EventLog.activity(
            LogEvents.service(
                System.currentTimeMillis(), COMP, "connected",
                "section_rules=$hasSectionRules,blocked_apps=${blockedApps.size},log_urls=$logUrls",
            ),
            urgent = true,
        )
        Monitor.attach(this, COMP)
        ScreenCapture.refresh(this)
        CaptureIndicator.refresh(this)
    }

    override fun onDestroy() {
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
        blockedApps = BlockedAppsStore.get(this)
        logUrls = LogSettings.logUrls(this)
        captureApps = MonitorSettings.watchedApps(this)
        captureScreenshots = MonitorSettings.screenshotsEnabled(this)
        captureText = MonitorSettings.textEnabled(this)
        applyEventFilter()
        ScreenCapture.refresh(this)
        // The indicator must reflect capture state exactly: show while capturing,
        // gone otherwise. Never hidden while capture is on (self-accountability).
        CaptureIndicator.refresh(this)
        // Managed mode: keep OS-level app suspension in step with blocked apps.
        AppSuspension.reconcile(this)
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
        val capturing = captureApps.isNotEmpty() && (captureScreenshots || captureText)
        val watchBrowsers = hasSectionRules || logUrls
        val targets = HashSet<String>()
        if (watchBrowsers) targets += URL_BAR_IDS.keys
        targets += blockedApps
        if (capturing) targets += captureApps
        info.packageNames =
            if (targets.isEmpty()) arrayOf(packageName) else targets.toTypedArray()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (watchBrowsers || capturing) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        // Batch event delivery: URL changes are human-speed, no need for more
        // than a couple of wakeups per second while scrolling.
        info.notificationTimeout = 600
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName) return
        Monitor.perf.count("a11y_ev")

        // Whole-app blocking: bounce to the home screen.
        if (eventPackage in blockedApps) {
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
        if (eventPackage in captureApps) maybeCapture(eventPackage)

        // Section rules and URL recording: only meaningful inside known browsers.
        if (!(hasSectionRules || logUrls) || eventPackage !in URL_BAR_IDS) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCheckAt < EVENT_THROTTLE_MS) return
        lastCheckAt = now

        val url = findUrl(eventPackage)
        if (url == null) {
            // A browser event with no readable address bar: either the page has
            // no toolbar showing (fullscreen, menus) or the browser changed its
            // view IDs. A high rate here is the signal to update URL_BAR_IDS.
            Monitor.perf.count("url_miss")
            return
        }
        Monitor.perf.count("url_read")
        val parts = LogEvents.splitUrl(url)
        if (logUrls && parts != null && urlDeduper.accept(eventPackage, parts.first, parts.second)) {
            EventLog.activity(
                LogEvents.url(System.currentTimeMillis(), eventPackage, parts.first, parts.second)
            )
        }
        if (!hasSectionRules || !UrlFilter.shouldBlock(url, rules)) return

        if (now - lastBackAt >= BACK_DEBOUNCE_MS) {
            lastBackAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, R.string.section_blocked_toast, Toast.LENGTH_SHORT).show()
            EventLog.activity(
                LogEvents.enforce(
                    System.currentTimeMillis(), "section_back", eventPackage,
                    parts?.first, parts?.second,
                )
            )
        }
    }

    /**
     * Screenshot and/or text capture for a watched app. Screenshot timing is the
     * pure CapturePolicy inside ScreenCapture; text is throttled here and
     * de-duplicated so a scrolling screen isn't logged repeatedly.
     */
    private fun maybeCapture(pkg: String) {
        if (captureScreenshots && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ScreenCapture.onWatchedWindow(this, pkg)
        }
        if (captureText) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTextCaptureAt >= TEXT_CAPTURE_THROTTLE_MS) {
                lastTextCaptureAt = now
                TextCapture.capture(pkg, rootInActiveWindow)
            }
        }
    }

    override fun onInterrupt() {
        EventLog.diag(LogEvents.error(System.currentTimeMillis(), COMP, "interrupt"))
    }

    private fun findUrl(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        URL_BAR_IDS[packageName]?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull()?.text?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
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
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
