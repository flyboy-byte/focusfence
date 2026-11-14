package app.focusfence.dns

/**
 * Turns raw UsageStats events into a clean foreground timeline.
 *
 * UsageStats reports per-*activity* resume/pause, so one app opening three
 * screens produces a burst of pause/resume pairs, and the pause of app A
 * arrives slightly before the resume of app B. Only changes of the
 * foreground *package* matter for accountability, so this emits:
 *
 * - "fg" when a different package is resumed (preceded by "bg" for the old one),
 * - "bg" + screen "off" when the screen stops being interactive,
 * - screen "on" when it becomes interactive again.
 *
 * Pauses are ignored on purpose: the next resume (or screen-off) settles who
 * is in front. Pure state machine; event-type ints match UsageEvents.Event.
 */
class AppTimeline(private val ignoredPackages: Set<String> = emptySet()) {

    companion object {
        const val ACTIVITY_RESUMED = 1
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16
    }

    sealed class Out {
        data class App(val t: Long, val pkg: String, val state: String) : Out()
        data class Screen(val t: Long, val state: String) : Out()
    }

    var foreground: String? = null
        private set

    fun feed(t: Long, type: Int, pkg: String?): List<Out> = when (type) {
        ACTIVITY_RESUMED -> {
            if (pkg == null || pkg in ignoredPackages || pkg == foreground) {
                emptyList()
            } else {
                val out = ArrayList<Out>(2)
                foreground?.let { out += Out.App(t, it, "bg") }
                foreground = pkg
                out += Out.App(t, pkg, "fg")
                out
            }
        }
        SCREEN_NON_INTERACTIVE -> {
            val out = ArrayList<Out>(2)
            foreground?.let { out += Out.App(t, it, "bg") }
            foreground = null
            out += Out.Screen(t, "off")
            out
        }
        SCREEN_INTERACTIVE -> listOf(Out.Screen(t, "on"))
        else -> emptyList()
    }
}
