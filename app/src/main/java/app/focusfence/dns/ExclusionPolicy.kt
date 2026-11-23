package app.focusfence.dns

/**
 * Which apps may be excluded from the VPN without friction.
 *
 * Excluding an app sends its traffic around the filter, so it is a loosening.
 * Some apps simply refuse to run behind a VPN (games with anti-cheat, banking),
 * and those can't show porn anyway: forcing a 10-minute wait to play a game
 * would only teach the owner to switch the whole filter off. So the rule is by
 * what the app can *reach*:
 *
 *  - [Risk.FREE]: apps that can't browse arbitrary content (games, music, maps).
 *    Toggling is instant, even in strict mode.
 *  - [Risk.GUARDED]: everything that could show adult content (browsers, social,
 *    video, photos, anything uncategorised). Excluding one goes through the
 *    normal friction (strict mode: the timed unlock).
 *
 * Unknown means guarded; the safe direction. A game with an in-app browser or
 * open chat is listed in [OPEN_GAMES] because Android's "game" category alone
 * would wave it through. Pure, so it is unit-tested.
 */
object ExclusionPolicy {

    enum class Risk { FREE, GUARDED }

    // android.content.pm.ApplicationInfo.CATEGORY_* (API 26), mirrored to stay pure.
    private const val CATEGORY_GAME = 0
    private const val CATEGORY_AUDIO = 1
    private const val CATEGORY_MAPS = 6

    /** Declared "game" but reach open web content or strangers' uploads. */
    private val OPEN_GAMES = setOf(
        "com.roblox.client",
        "com.epicgames.portal",
        "com.discord",
    )

    fun classify(pkg: String, androidCategory: Int, isBrowser: Boolean, group: WatchScope.Group): Risk {
        if (isBrowser || pkg in OPEN_GAMES) return Risk.GUARDED
        // Apps WatchScope knows are social/video/images stay guarded whatever they declare.
        if (group == WatchScope.Group.SOCIAL || group == WatchScope.Group.VIDEO ||
            group == WatchScope.Group.IMAGES || group == WatchScope.Group.BROWSERS
        ) return Risk.GUARDED
        return when (androidCategory) {
            CATEGORY_GAME, CATEGORY_AUDIO, CATEGORY_MAPS -> Risk.FREE
            else -> Risk.GUARDED
        }
    }

    /** Exclusions being *added* that need friction (removals are tightening: never guarded). */
    fun needsGuard(before: Set<String>, after: Set<String>, risk: (String) -> Risk): Set<String> =
        (after - before).filterTo(HashSet()) { risk(it) == Risk.GUARDED }
}
