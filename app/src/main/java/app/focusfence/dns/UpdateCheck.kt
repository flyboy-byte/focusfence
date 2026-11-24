package app.focusfence.dns

/**
 * Decides whether a GitHub release is an update worth offering. Pure, so it is tested on the JVM; the worker does
 * the network call and JSON parsing, and the installer does the signature check against the installed app.
 *
 * Only full releases count (no drafts or pre-releases), only a newer version than the one installed, and only an
 * APK served from this repo's own release downloads, so a changed API answer can't point the phone elsewhere.
 */
object UpdateCheck {
    /** Where releases come from. A fork that publishes its own releases changes this one line. */
    const val REPO = "flyboy-byte/focusfence"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    const val CHECK_EVERY_MS = 24 * 3_600_000L

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<Asset>,
        val pageUrl: String = "",
    )

    data class Offer(val version: String, val tag: String, val apkUrl: String, val size: Long, val pageUrl: String)

    /** "v2.13.0-accountable" -> "2.13.0-accountable". */
    fun versionOf(tag: String): String = tag.removePrefix("v")

    fun offer(release: Release, installedVersion: String): Offer? {
        if (release.draft || release.prerelease) return null
        val version = versionOf(release.tag)
        if (Digest.compareVersions(version, installedVersion) <= 0) return null
        // compareVersions treats unparseable as equal, so a garbage tag already stopped above.
        val apk = release.assets.firstOrNull { isOurApk(it, release.tag) } ?: return null
        return Offer(version, release.tag, apk.url, apk.size, release.pageUrl)
    }

    private fun isOurApk(a: Asset, tag: String): Boolean =
        a.name.endsWith(".apk") &&
            a.name.contains("accountable") &&
            a.url.startsWith(DOWNLOAD_PREFIX + tag + "/")

    /** True when a check is due: never checked, a day has passed, or the clock went backwards. */
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs

    /** Signing certificates match when the downloaded APK's set equals the installed app's (hex SHA-256, any case). */
    fun sameSigner(installed: Collection<String>, downloaded: Collection<String>): Boolean {
        val a = installed.map { it.lowercase() }.toSet()
        val b = downloaded.map { it.lowercase() }.toSet()
        return a.isNotEmpty() && a == b
    }
}
