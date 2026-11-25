package app.focusfence.dns

/**
 * The app–server protocol version (D-041, 2026-11-25). Shared: the server compiles this file too, so both ends read
 * the same numbers. [LEVEL] goes up only when a request or response the other side depends on changes shape (a path,
 * a field, a signature format), not on every release. Each side keeps working with the other's last [WINDOW] levels:
 * a server at level 7 still serves apps at 5 and 6, and an app at 7 still talks to servers at 5 and 6.
 *
 * CI holds the server to it: `server/src/test/.../compat/` has one suite per level still in the window, written with
 * literal JSON as that level's app sends it, and `CompatWindowTest` fails if one is missing. Bumping [LEVEL] means
 * adding the new level's suite and keeping the old ones passing until they fall out of the window.
 */
object Protocol {
    const val LEVEL = 1
    const val WINDOW = 2
    /** The oldest level of the other side this side still supports. */
    val OLDEST: Int get() = maxOf(1, LEVEL - WINDOW)

    /** Request header the app sends; response headers the server sends back. */
    const val HEADER_APP = "X-FF-Protocol"
    const val HEADER_SERVER = "X-FF-Server-Protocol"
    const val HEADER_SERVER_OLDEST = "X-FF-Server-Oldest"

    enum class Status { OK, SERVER_TOO_OLD, APP_TOO_OLD, UNKNOWN }

    /**
     * Whether this app (at [appLevel], supporting servers back to [appOldest]) and a server (at [serverLevel], serving
     * apps back to [serverOldest]) are inside each other's window. A server that sends no level predates this: level 1.
     */
    fun check(appLevel: Int, appOldest: Int, serverLevel: Int?, serverOldest: Int?): Status {
        val level = serverLevel ?: return Status.UNKNOWN
        return when {
            level < appOldest -> Status.SERVER_TOO_OLD
            appLevel < (serverOldest ?: level) -> Status.APP_TOO_OLD
            else -> Status.OK
        }
    }
}
