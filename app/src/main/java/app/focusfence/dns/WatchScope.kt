package app.focusfence.dns

/**
 * Which apps screenshot/text capture watches. Default is **all apps**, minus
 * things that are never meaningful to capture (home screen, system UI, the
 * keyboard, Focus Fence itself — supplied by the caller as [alwaysSkip]) and
 * any the owner excluded. Alternatively the owner picks apps, helped by
 * automatic [Group]s so "watch browsers and social apps" is two taps.
 * Pure, so the rules are tested.
 */
object WatchScope {

    enum class Mode { ALL, CHOSEN }

    enum class Group(val label: String) {
        BROWSERS("Browsers"),
        SOCIAL("Social & messaging"),
        VIDEO("Video"),
        IMAGES("Photos & images"),
        GAMES("Games"),
        OTHER("Other apps"),
    }

    fun watches(pkg: String, mode: Mode, chosen: Set<String>, excluded: Set<String>, alwaysSkip: Set<String>): Boolean =
        pkg !in alwaysSkip && pkg !in excluded && (mode == Mode.ALL || pkg in chosen)

    /**
     * Which apps screen checks watch, in words for partners ("all apps except Signal"): kept in the integrity trail, so
     * excluding an app, or watching fewer, is never silent (2026-11-25, Logan: "guarded and told").
     */
    fun describe(mode: Mode, chosen: List<String>, excluded: List<String>): String {
        fun names(l: List<String>): String {
            val s = l.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            return if (s.size <= 3) s.joinToString(", ") else s.take(3).joinToString(", ") + " and ${s.size - 3} more"
        }
        return when {
            mode == Mode.CHOSEN && chosen.isEmpty() -> "no apps"
            mode == Mode.CHOSEN -> "only " + names(chosen)
            excluded.isEmpty() -> "all apps"
            else -> "all apps except " + names(excluded)
        }
    }

    // android.content.pm.ApplicationInfo.CATEGORY_* (API 26), mirrored so this stays pure.
    private const val CATEGORY_GAME = 0
    private const val CATEGORY_VIDEO = 2
    private const val CATEGORY_IMAGE = 3
    private const val CATEGORY_SOCIAL = 4

    /**
     * Many apps don't declare a category, so well-known packages are mapped
     * directly. Browsers are detected by the caller (they handle web links) and
     * win over everything else.
     */
    private val KNOWN = mapOf(
        "com.facebook.katana" to Group.SOCIAL, "com.facebook.lite" to Group.SOCIAL,
        "com.instagram.android" to Group.SOCIAL, "com.instagram.barcelona" to Group.SOCIAL,
        "com.zhiliaoapp.musically" to Group.VIDEO, "com.ss.android.ugc.trill" to Group.VIDEO,
        "com.snapchat.android" to Group.SOCIAL, "com.twitter.android" to Group.SOCIAL,
        "com.reddit.frontpage" to Group.SOCIAL, "com.discord" to Group.SOCIAL,
        "org.telegram.messenger" to Group.SOCIAL, "com.whatsapp" to Group.SOCIAL,
        "com.facebook.orca" to Group.SOCIAL, "com.tumblr" to Group.SOCIAL,
        "com.pinterest" to Group.IMAGES, "com.tinder" to Group.SOCIAL, "com.bumble.app" to Group.SOCIAL,
        "com.google.android.youtube" to Group.VIDEO, "com.netflix.mediaclient" to Group.VIDEO,
        "tv.twitch.android.app" to Group.VIDEO, "com.vimeo.android.videoapp" to Group.VIDEO,
        "com.google.android.apps.photos" to Group.IMAGES,
    )

    fun group(pkg: String, androidCategory: Int, isBrowser: Boolean): Group {
        if (isBrowser) return Group.BROWSERS
        KNOWN[pkg]?.let { return it }
        return when (androidCategory) {
            CATEGORY_GAME -> Group.GAMES
            CATEGORY_VIDEO -> Group.VIDEO
            CATEGORY_IMAGE -> Group.IMAGES
            CATEGORY_SOCIAL -> Group.SOCIAL
            else -> Group.OTHER
        }
    }

    /** One-line summary for the Monitoring screen. */
    fun describe(mode: Mode, chosen: Int, excluded: Int): String = when (mode) {
        Mode.ALL -> if (excluded == 0) "All apps" else "All apps except $excluded"
        Mode.CHOSEN -> "$chosen chosen app" + if (chosen == 1) "" else "s"
    }
}
