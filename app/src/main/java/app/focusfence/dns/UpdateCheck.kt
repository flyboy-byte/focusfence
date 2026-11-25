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
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    /** Same as Tokalot: at most every 3 hours, so a release reaches the phone the same day. */
    const val CHECK_EVERY_MS = 3 * 3_600_000L

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<Asset>,
        val pageUrl: String = "",
        val body: String = "",
    )

    data class Offer(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val size: Long,
        val pageUrl: String,
        /** Plain "What's new" text for the owner ([userNotes]); empty when the release has none. */
        val notes: String = "",
    )

    /** App releases are tagged `vX.Y.Z-...`; the server's own releases (`server-vX.Y.Z`) share the repo and never count. */
    fun isAppTag(tag: String): Boolean = tag.startsWith("v") && tag.getOrNull(1)?.isDigit() == true

    /** "v2.13.0-accountable" -> "2.13.0-accountable". */
    fun versionOf(tag: String): String = tag.removePrefix("v")

    fun offer(release: Release, installedVersion: String): Offer? {
        if (release.draft || release.prerelease || !isAppTag(release.tag)) return null
        val version = versionOf(release.tag)
        if (Digest.compareVersions(version, installedVersion) <= 0) return null
        // compareVersions treats unparseable as equal, so a garbage tag already stopped above.
        val apk = release.assets.firstOrNull { isOurApk(it, release.tag) } ?: return null
        return Offer(version, release.tag, apk.url, apk.size, release.pageUrl, userNotes(release.body))
    }

    private val HIDDEN_SECTIONS = setOf("docs", "maintenance", "tests", "ci")
    private val REF = Regex("""\s*\(`[0-9a-f]{7,}`(, \[#\d+]\([^)]*\))?\)\s*$""")
    /** `**ui:** ` from a scoped title, or a bare `ui: ` / `Server: ` prefix from an untyped one (any case). */
    private val SCOPE = Regex("""^(?:\*\*([\w-]+):\*\*|([a-zA-Z][\w-]{1,15}):)\s+""")
    /** Lines about these parts aren't changes to the app on the phone, so the phone doesn't list them. */
    private val HIDDEN_SCOPES = setOf("server", "ci", "docs", "build", "deps", "tools", "release")

    /** "2.16.0-accountable" -> "2.16.0", for people. */
    fun shortVersion(version: String): String = version.removePrefix("v").substringBefore('-')

    /**
     * The release body's "## What's new" section as plain text for the phone: section names kept ("Added",
     * "Fixed"), developer-only sections dropped, and each line stripped of its commit and PR reference and
     * `**scope:**` prefix. Empty when the body has no such section (releases before 2.15).
     */
    fun userNotes(body: String): String {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().equals("## What's new", ignoreCase = true) }
        if (start < 0) return ""
        val out = ArrayList<String>()
        var keep = true
        for (raw in lines.drop(start + 1)) {
            val line = raw.trim()
            if (line.startsWith("## ")) break
            when {
                line.startsWith("### ") -> {
                    val name = line.removePrefix("### ").trim()
                    keep = name.lowercase() !in HIDDEN_SECTIONS
                    if (keep) out += name
                }
                keep && line.startsWith("- ") -> {
                    val noRef = line.removePrefix("- ").replace(REF, "")
                    val scope = SCOPE.find(noRef)?.let { (it.groupValues[1] + it.groupValues[2]).lowercase() }
                    val text = noRef.replace(SCOPE, "").trim()
                    if (text.isNotEmpty() && scope !in HIDDEN_SCOPES) out += "• " + text.replaceFirstChar { it.uppercase() }
                }
            }
        }
        // A section heading left with no lines under it says nothing.
        val cleaned = out.filterIndexed { i, l -> l.startsWith("• ") || out.getOrNull(i + 1)?.startsWith("• ") == true }
        return cleaned.joinToString("\n")
    }

    private fun isOurApk(a: Asset, tag: String): Boolean =
        a.name.endsWith(".apk") &&
            a.name.contains("accountable") &&
            a.url.startsWith(DOWNLOAD_PREFIX + tag + "/")

    /** True when a check is due: never checked, [CHECK_EVERY_MS] has passed, or the clock went backwards. */
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs

    /** Signing certificates match when the downloaded APK's set equals the installed app's (hex SHA-256, any case). */
    fun sameSigner(installed: Collection<String>, downloaded: Collection<String>): Boolean {
        val a = installed.map { it.lowercase() }.toSet()
        val b = downloaded.map { it.lowercase() }.toSet()
        return a.isNotEmpty() && a == b
    }
}
