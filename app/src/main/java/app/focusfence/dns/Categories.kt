package app.focusfence.dns

/**
 * Curated filter **bundles** and the decision precedence over them.
 *
 * The product choice (see docs/specs/bundles.md) is bundles over hand-curated
 * allow/deny lists: the owner toggles a few well-maintained lists Off / Report
 * / Block, and never edits individual domains. A bundle labels a host; the
 * per-bundle [Policy] decides what happens — classification is kept separate
 * from policy, so a list calling a domain "adult" never on its own means
 * "block forever".
 *
 * Precedence, highest first (see [decide]): a blocking user rule; the DoH
 * guards; the user allowlist; a BLOCK-policy bundle; the ad list. The allowlist
 * overrides *imported lists* (bundles, ads) for false positives only — never
 * the user's own rules and never the guards, which protect the filter itself.
 * Pure JVM.
 */
object Categories {

    private const val RAW = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"

    enum class Policy { OFF, REPORT, BLOCK;
        companion object {
            fun parse(s: String?): Policy = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: OFF
        }
    }

    enum class Kind { SECURITY, BYPASS, CONTENT }

    /**
     * One toggleable bundle. [url] is the on-device default (a size-optimised
     * "mini"/onlydomains variant where one exists, to keep the phone's matcher
     * small); [fullUrl] is a heavier opt-in variant. [defaultPolicy] is applied
     * until the owner changes it, so security-class bundles are on out of the
     * box. [tiered] bundles take their URL from the chosen [BaseTier] instead
     * of [url]. All HaGeZi lists are GPL-3.0 and downloaded at runtime (never
     * bundled into the APK), so they impose no license on this repo.
     */
    data class Bundle(
        val id: String,
        val label: String,
        val description: String,
        val kind: Kind,
        val defaultPolicy: Policy,
        val url: String,
        val fullUrl: String? = null,
        val license: String = "GPL-3.0 (HaGeZi)",
        val tiered: Boolean = false,
        /**
         * Part of the app's purpose, porn accountability, rather than extra blocking on top of it (Logan, 2026-11-25:
         * the lists exist "to add general blocking on top of nudity and porn accountability filtering"). A core list
         * is Report or Block, never Off: [effectivePolicy].
         */
        val core: Boolean = false,
    )

    /** What a stored policy means: a core list stored as Off (older versions allowed it) counts as Report. */
    fun effectivePolicy(bundle: Bundle?, stored: Policy): Policy =
        if (bundle?.core == true && stored == Policy.OFF) Policy.REPORT else stored

    /** Strictness of the base Security bundle. Mini variants on device by default. */
    /** HaGeZi's tiers under plain names (2.24): Light, Standard, Strong, Strongest. */
    enum class BaseTier(val label: String, val blurb: String, val url: String) {
        LIGHT("Light", "Catches the least, almost never blocks something you need", "$RAW/light-onlydomains.txt"),
        PRO("Standard", "Recommended: a good balance", "$RAW/pro.mini-onlydomains.txt"),
        PRO_FULL("Strong", "More coverage, a bigger list", "$RAW/pro-onlydomains.txt"),
        ULTIMATE("Strongest", "Catches the most, may break some sites", "$RAW/ultimate.mini-onlydomains.txt");

        companion object {
            val DEFAULT = PRO
            fun parse(s: String?): BaseTier = values().firstOrNull { it.name == s } ?: DEFAULT
        }
    }

    /**
     * The curated set. Security + Threat + Bypass default to BLOCK (the bypass
     * bundle is the accountability-critical one: it stops DoH/VPN/Tor/proxy
     * circumvention of the filter). Content bundles default Off; the owner
     * turns them to Report or Block.
     */
    val BUNDLES = listOf(
        Bundle(
            "security", "Ads, trackers & malware",
            "The base list. Strength sets how much it catches.",
            Kind.SECURITY, Policy.BLOCK, url = BaseTier.DEFAULT.url, tiered = true,
        ),
        Bundle(
            "threat", "Malware & phishing",
            "Extra dangerous sites from a live threat feed.",
            Kind.SECURITY, Policy.BLOCK,
            url = "$RAW/tif.mini-onlydomains.txt", fullUrl = "$RAW/tif.medium-onlydomains.txt",
        ),
        Bundle(
            "bypass", "Ways around the filter",
            "Private DNS, VPNs, proxies and Tor. Keep this on Block for accountability.",
            Kind.BYPASS, Policy.BLOCK, url = "$RAW/doh-vpn-proxy-bypass-onlydomains.txt", core = true,
        ),
        Bundle(
            "adult", "Adult",
            "Porn and other adult sites.",
            // BLOCK by default since 2.25 (Logan, 2026-11-25): blocking adult sites is the app's purpose. Report stays
            // one tap away, behind the strict-mode guard.
            Kind.CONTENT, Policy.BLOCK, url = "$RAW/nsfw-onlydomains.txt", core = true,
        ),
        Bundle(
            "gambling", "Gambling",
            "Betting and casino sites.",
            Kind.CONTENT, Policy.REPORT,
            url = "$RAW/gambling.mini-onlydomains.txt", fullUrl = "$RAW/gambling-onlydomains.txt",
        ),
        Bundle(
            "social", "Social media",
            "Social networks.",
            Kind.CONTENT, Policy.OFF, url = "$RAW/social-onlydomains.txt",
        ),
        Bundle(
            "piracy", "Piracy",
            "Illegal streaming and downloads.",
            Kind.CONTENT, Policy.OFF, url = "$RAW/anti.piracy-onlydomains.txt",
        ),
        Bundle(
            "scam", "Scams & fake sites",
            "Fake shops, scams and rip-offs.",
            Kind.CONTENT, Policy.OFF, url = "$RAW/fake-onlydomains.txt",
        ),
    )

    fun bundle(id: String): Bundle? = BUNDLES.firstOrNull { it.id == id }

    /** Bundles (sorted) whose list contains [qname] or a parent of it. */
    fun match(qname: String, lists: Map<String, Set<String>>): List<String> {
        if (lists.isEmpty()) return emptyList()
        var out: MutableList<String>? = null
        for ((cat, hosts) in lists) {
            if (Blocklist.matches(qname, hosts)) {
                if (out == null) out = ArrayList(2)
                out += cat
            }
        }
        return out?.sorted() ?: emptyList()
    }

    data class Decision(
        val blocks: Boolean,
        /** LogEvents.REASON_* of the deciding layer, or null for no match. */
        val reason: String?,
        val rule: BlockRule?,
        val categories: List<String>,
    )

    /**
     * The whole precedence in one pure function. [ruleBlocks] is the
     * (side-effecting, budget-accruing) user-rule check and is called at most once.
     */
    fun decide(
        qname: String,
        rule: BlockRule?,
        ruleBlocks: (BlockRule) -> Boolean,
        allowlist: Set<String>,
        categoryLists: Map<String, Set<String>>,
        policies: Map<String, Policy>,
        adHosts: Set<String>,
        guards: Set<String>,
    ): Decision {
        val cats = match(qname, categoryLists).filter { (policies[it] ?: Policy.OFF) != Policy.OFF }
        if (rule != null && ruleBlocks(rule)) return Decision(true, LogEvents.REASON_RULE, rule, cats)
        if (Blocklist.matches(qname, guards)) return Decision(true, LogEvents.REASON_GUARD, rule, cats)
        if (allowlist.isNotEmpty() && Blocklist.matches(qname, allowlist)) {
            return Decision(false, LogEvents.REASON_ALLOW, rule, cats)
        }
        if (cats.any { policies[it] == Policy.BLOCK }) return Decision(true, LogEvents.REASON_CATEGORY, rule, cats)
        if (adHosts.isNotEmpty() && qname in adHosts) return Decision(true, LogEvents.REASON_ADS, rule, cats)
        val reason = when {
            rule != null -> LogEvents.REASON_RULE
            cats.isNotEmpty() -> LogEvents.REASON_CATEGORY
            else -> null
        }
        return Decision(false, reason, rule, cats)
    }
}
