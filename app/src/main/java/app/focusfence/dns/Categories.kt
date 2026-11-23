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
    )

    /** Strictness of the base Security bundle. Mini variants on device by default. */
    enum class BaseTier(val label: String, val url: String) {
        LIGHT("Light — lightest, fewest false positives", "$RAW/light-onlydomains.txt"),
        PRO("Pro — recommended", "$RAW/pro.mini-onlydomains.txt"),
        PRO_FULL("Pro (full) — larger, more coverage", "$RAW/pro-onlydomains.txt"),
        ULTIMATE("Ultimate — aggressive, may break sites", "$RAW/ultimate.mini-onlydomains.txt");

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
            "security", "Security — ads, trackers, malware",
            "The base list. Blocks ads, trackers and known-bad domains. Strictness is set by the tier below.",
            Kind.SECURITY, Policy.BLOCK, url = BaseTier.DEFAULT.url, tiered = true,
        ),
        Bundle(
            "threat", "Threat intelligence (malware / phishing)",
            "HaGeZi's threat-intelligence feed. Extra malware and phishing coverage.",
            Kind.SECURITY, Policy.BLOCK,
            url = "$RAW/tif.mini-onlydomains.txt", fullUrl = "$RAW/tif.medium-onlydomains.txt",
        ),
        Bundle(
            "bypass", "Bypass guard (DoH / VPN / Tor / proxy)",
            "Blocks the public resolvers, proxies and VPN/Tor bootstrap hosts used to route around the filter. Recommended for accountability.",
            Kind.BYPASS, Policy.BLOCK, url = "$RAW/doh-vpn-proxy-bypass-onlydomains.txt",
        ),
        Bundle(
            "adult", "Adult / NSFW",
            "Adult content domains.",
            // REPORT by default: detected and reported, not blocked — the
            // accountability default; blocking stays a choice.
            Kind.CONTENT, Policy.REPORT, url = "$RAW/nsfw-onlydomains.txt",
        ),
        Bundle(
            "gambling", "Gambling",
            "Gambling and betting domains.",
            Kind.CONTENT, Policy.REPORT,
            url = "$RAW/gambling.mini-onlydomains.txt", fullUrl = "$RAW/gambling-onlydomains.txt",
        ),
        Bundle(
            "social", "Social media",
            "Social-network domains.",
            Kind.CONTENT, Policy.OFF, url = "$RAW/social-onlydomains.txt",
        ),
        Bundle(
            "piracy", "Piracy",
            "Piracy and illegal-streaming domains.",
            Kind.CONTENT, Policy.OFF, url = "$RAW/anti.piracy-onlydomains.txt",
        ),
        Bundle(
            "scam", "Scam & fake sites",
            "Scams, fake shops, fake news and rip-offs.",
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
