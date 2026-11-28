package app.focusfence.dns

/**
 * What goes into the diag log when something breaks: crash stacks, why Android ended the process, and which way a
 * connection failed. Pure, so the rules below are tested: nothing here keeps an exception message from a crash, a
 * path, a query or a token, because exports get shared to debug the app.
 */
object Faults {

    private const val MAX_STACK = 1500
    private const val TOP_FRAMES = 3
    private const val APP_FRAMES = 8
    private const val MAX_CAUSES = 4

    /** How a connection failed: dns / timeout / connect / tls / io, or "other" for anything that isn't I/O. */
    fun netKind(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "dns"
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "connect"
        is javax.net.ssl.SSLException -> "tls"
        is java.io.IOException -> "io"
        else -> "other"
    }

    /** The exception classes, outermost first: "IllegalStateException < IOException". Never the messages. */
    fun exceptionChain(e: Throwable): String = causes(e).joinToString(" < ") { it.javaClass.simpleName.ifEmpty { it.javaClass.name } }

    /**
     * Where it happened: for each exception in the chain, the top frames (whoever threw) plus the app's own frames.
     * "at Class.method:line" only; messages can carry what was on screen or an address.
     */
    fun stack(e: Throwable, appPackage: String = "app.focusfence"): String = causes(e).joinToString(" | ") { c ->
        val frames = c.stackTrace
        val keep = frames.withIndex()
            .filter { (i, f) -> i < TOP_FRAMES || f.className.startsWith(appPackage) }
            .take(TOP_FRAMES + APP_FRAMES)
            .joinToString(" ") { (_, f) -> "${f.className.removePrefix("$appPackage.")}.${f.methodName}:${f.lineNumber}" }
        "${c.javaClass.simpleName} at $keep"
    }.take(MAX_STACK)

    private fun causes(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSES).toList()

    /** `ApplicationExitInfo.getReason()` in words. */
    fun exitReason(code: Int): String = EXIT_REASONS.getOrNull(code) ?: "code_$code"

    private val EXIT_REASONS = listOf(
        "unknown", "exit_self", "signaled", "low_memory", "crash", "crash_native", "anr", "init_failure",
        "permission_change", "excessive_resource", "user_requested", "user_stopped", "dependency_died", "other",
        "freezer", "package_state_change", "package_updated",
    )

    /** Android's note on an exit. Dropped for crashes: there it is the exception message. */
    fun exitNote(code: Int, description: String?): String? =
        if (exitReason(code).startsWith("crash")) null else description?.takeIf { it.isNotBlank() }?.let { scrub(it).take(160) }

    private val URL = Regex("""\b([a-z][a-z0-9+.-]*://[^/\s?#"']+)[^\s"']*""", RegexOption.IGNORE_CASE)
    private val PATH = Regex("""(?<![\w.])/(p|j|c)/[^\s"']+""")
    private val SECRET = Regex("""\b(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}={0,2}""")

    /** Error text safe to keep: addresses cut to the host, invite and report paths and long keys removed. */
    fun scrub(text: String): String = text
        .replace(URL) { "${it.groupValues[1]}/…" }
        .replace(PATH) { "/${it.groupValues[1]}/…" }
        .replace(SECRET, "…")
}

/** Lets one error line through until its text changes or [everyMs] pass: a phone offline all night writes one line. */
class Repeats(private val everyMs: Long = 6 * 3_600_000L) {
    private val last = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun allow(key: String, text: String, now: Long): Boolean {
        val prev = last[key]
        if (prev != null && prev.first == text && now - prev.second < everyMs) return false
        last[key] = text to now
        return true
    }
}
