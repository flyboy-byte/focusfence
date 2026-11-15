package app.focusfence.monitor

import android.view.accessibility.AccessibilityNodeInfo
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UrlDeduper

/**
 * Extracts visible on-screen text from a watched app for the accountability
 * record — the "content inside apps" signal. A bounded walk of the node tree
 * (capped at [MAX_NODES]) collects text nodes; the result is length-capped and
 * de-duplicated so the same screen isn't logged repeatedly.
 *
 * Scope is the owner's chosen watched apps only, and only while the capture
 * indicator is showing. Password fields are skipped. Like screenshots, this is
 * self-accountability data, visible to the person via the indicator.
 */
object TextCapture {

    private const val MAX_NODES = 400
    private const val MAX_TEXT_CHARS = 2000

    private val deduper = UrlDeduper()

    fun capture(pkg: String, root: AccessibilityNodeInfo?) {
        if (root == null) return
        val sb = StringBuilder()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || visited >= MAX_NODES || sb.length >= MAX_TEXT_CHARS) return
            visited++
            if (!node.isPassword) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" · ")
                    sb.append(t)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)

        if (sb.isEmpty()) {
            Monitor.perf.count("text_empty")
            return
        }
        val text = sb.toString().take(MAX_TEXT_CHARS)
        // Dedup keyed by a short digest so near-identical screens collapse.
        if (!deduper.accept(pkg, "text", text.hashCode().toString())) return
        Monitor.perf.count("text_captured")
        EventLog.activity(LogEvents.text(System.currentTimeMillis(), pkg, text))
    }
}
