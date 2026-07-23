package app.focusfence.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.focusfence.R
import app.focusfence.data.BlocklistStore
import app.focusfence.dns.BlockRule
import app.focusfence.dns.UrlFilter

/**
 * Enforces "allow only specific sections" rules by watching browser address
 * bars. When the current URL falls under a section rule but outside its allowed
 * paths, the service presses Back. DNS cannot see URL paths (TLS encrypts
 * them), so this is the only self-contained way to block part of a website
 * while allowing another part.
 *
 * Reads only browser address bars for matching against the user's own rules;
 * nothing is stored or transmitted.
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

        private const val EVENT_THROTTLE_MS = 250L
        private const val BACK_DEBOUNCE_MS = 1500L
        private const val MAX_FALLBACK_NODES = 250
    }

    @Volatile
    private var rules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var hasSectionRules = false

    private var lastCheckAt = 0L
    private var lastBackAt = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BlocklistStore.KEY_RULES) reloadRules()
    }

    override fun onServiceConnected() {
        reloadRules()
        BlocklistStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        BlocklistStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    private fun reloadRules() {
        val loaded = BlocklistStore.rulesByDomain(this)
        rules = loaded
        hasSectionRules = loaded.values.any { it.hasPathExceptions }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!hasSectionRules) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCheckAt < EVENT_THROTTLE_MS) return
        lastCheckAt = now

        val packageName = event.packageName?.toString() ?: return
        val url = findUrl(packageName) ?: return
        if (!UrlFilter.shouldBlock(url, rules)) return

        if (now - lastBackAt >= BACK_DEBOUNCE_MS) {
            lastBackAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, R.string.section_blocked_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

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
