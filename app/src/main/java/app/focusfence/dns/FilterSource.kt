package app.focusfence.dns

/**
 * A downloadable filter list the app knows how to consume. Pure data so the
 * catalog is JVM-testable; the Android side ([app.focusfence.data.FilterListStore])
 * owns files, prefs and downloads.
 *
 * Licensing per source was verified from primary sources on 2026-08-17
 * (research Q2, `docs/research/2026-08-17-filter-list-licensing-claude.md`).
 * The app only ever downloads lists at runtime from the maintainer's own
 * hosting — never vendors them into the repo or APK. That distinction is
 * load-bearing for StevenBlack's base list, whose upstreams include
 * non-commercial sources.
 */
data class FilterSource(
    /** Stable key: lowercase `[a-z0-9_]` only — it names the on-disk file, the
     *  prefs keys and the stats bucket, so it must never contain '=' ';' or ':'. */
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    /** Short human-readable license label shown in the UI next to the source. */
    val license: String,
    /** Where the license text lives; the UI links here for attribution. */
    val licenseUrl: String,
    val homepageUrl: String,
)

/**
 * The built-in source catalog, grouped by the categories the UI exposes as
 * toggles. Category ids are string constants rather than an enum so a future
 * boundary model (W8) can use them as a `Category` target without a type
 * migration.
 *
 * Order matters twice: the first source of a category is the one a category
 * toggle enables by default, and when several enabled sources claim the same
 * host the earliest source in this list wins the provenance credit.
 */
object FilterSources {

    const val CATEGORY_ADS = "ads"
    const val CATEGORY_ADULT = "adult"
    const val CATEGORY_GAMBLING = "gambling"
    const val CATEGORY_SOCIAL = "social"

    val CATEGORIES = listOf(CATEGORY_ADS, CATEGORY_ADULT, CATEGORY_GAMBLING, CATEGORY_SOCIAL)

    /** The id the legacy single-list install (pre-W11) migrates onto. */
    const val LEGACY_ID = "stevenblack"

    private const val SB_LICENSE_URL = "https://github.com/StevenBlack/hosts/blob/master/license.txt"
    private const val SB_HOME = "https://github.com/StevenBlack/hosts"
    private const val HAGEZI_LICENSE_URL = "https://github.com/hagezi/dns-blocklists/blob/main/LICENSE"
    private const val HAGEZI_HOME = "https://github.com/hagezi/dns-blocklists"
    private const val OISD_LICENSE_URL = "https://github.com/sjhgvr/oisd/blob/main/LICENSE"
    private const val OISD_HOME = "https://oisd.nl"

    val ALL: List<FilterSource> = listOf(
        // Ads & trackers. StevenBlack unified is the pre-W11 list, kept first so
        // existing installs keep their default. Its compilation is MIT but two
        // upstreams are non-commercial — fine for runtime download, never bundle.
        FilterSource(
            id = LEGACY_ID,
            name = "StevenBlack unified",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            category = CATEGORY_ADS,
            license = "MIT (mixed upstreams)",
            licenseUrl = SB_LICENSE_URL,
            homepageUrl = SB_HOME,
        ),
        FilterSource(
            id = "oisd_small",
            name = "oisd small",
            url = "https://small.oisd.nl/domainswild2",
            category = CATEGORY_ADS,
            license = "GPL-3.0",
            licenseUrl = OISD_LICENSE_URL,
            homepageUrl = OISD_HOME,
        ),
        // Adult content (D-016/W14). HaGeZi first: single-license GPL and the
        // most actively curated of the three.
        FilterSource(
            id = "hagezi_nsfw",
            name = "HaGeZi NSFW",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt",
            category = CATEGORY_ADULT,
            license = "GPL-3.0",
            licenseUrl = HAGEZI_LICENSE_URL,
            homepageUrl = HAGEZI_HOME,
        ),
        FilterSource(
            id = "oisd_nsfw",
            name = "oisd NSFW",
            url = "https://nsfw.oisd.nl/domainswild2",
            category = CATEGORY_ADULT,
            license = "GPL-3.0",
            licenseUrl = OISD_LICENSE_URL,
            homepageUrl = OISD_HOME,
        ),
        FilterSource(
            id = "stevenblack_porn",
            name = "StevenBlack porn",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            category = CATEGORY_ADULT,
            license = "MIT / CC BY sources",
            licenseUrl = SB_LICENSE_URL,
            homepageUrl = SB_HOME,
        ),
        // Gambling.
        FilterSource(
            id = "hagezi_gambling",
            name = "HaGeZi Gambling",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling-onlydomains.txt",
            category = CATEGORY_GAMBLING,
            license = "GPL-3.0",
            licenseUrl = HAGEZI_LICENSE_URL,
            homepageUrl = HAGEZI_HOME,
        ),
        FilterSource(
            id = "stevenblack_gambling",
            name = "StevenBlack gambling",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
            category = CATEGORY_GAMBLING,
            license = "MIT / CC BY sources",
            licenseUrl = SB_LICENSE_URL,
            homepageUrl = SB_HOME,
        ),
        // Social media.
        FilterSource(
            id = "stevenblack_social",
            name = "StevenBlack social",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts",
            category = CATEGORY_SOCIAL,
            license = "MIT / CC BY sources",
            licenseUrl = SB_LICENSE_URL,
            homepageUrl = SB_HOME,
        ),
    )

    private val byId: Map<String, FilterSource> = ALL.associateBy { it.id }

    fun byId(id: String): FilterSource? = byId[id]

    fun inCategory(category: String): List<FilterSource> = ALL.filter { it.category == category }

    /** The source a category toggle enables when none of its sources are on. */
    fun defaultFor(category: String): FilterSource? = ALL.firstOrNull { it.category == category }
}
