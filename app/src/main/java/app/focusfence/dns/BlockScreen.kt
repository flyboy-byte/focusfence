package app.focusfence.dns

/**
 * Decisions behind the "Blocked by Focus Fence" screen. Pure, so the rules that
 * keep it from popping up at the wrong moment are JVM-tested.
 *
 * Why a screen in our own app rather than a page in the browser: nearly every
 * site is HTTPS, so answering youtube.com with our own page would need a
 * certificate for youtube.com. The browser rejects that with a security
 * warning, and the only way around it (a user-installed CA) is a security hole
 * that most apps ignore anyway. So DNS still answers 0.0.0.0, and the screen is
 * opened (or a notification posted) alongside, after the accessibility
 * service, when it runs, has confirmed the browser in front is actually
 * showing the blocked site.
 */
object BlockScreen {

    /**
     * How a block is announced. NOTIFY is the default: lightest touch, and it
     * works without the accessibility service. SCREEN opens the full-screen
     * page; OFF leaves only the browser's own error.
     */
    enum class Style(val id: String) {
        NOTIFY("notify"), SCREEN("screen"), OFF("off");

        companion object {
            val DEFAULT = NOTIFY
            fun parse(id: String?): Style = values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** Which kind of rule did the blocking, for the screen's explanation line. */
    enum class Why { ALWAYS, SCHEDULE, BUDGET, COMMITMENT, SECTION }

    fun why(rule: BlockRule): Why = when {
        rule.hasPathExceptions -> Why.SECTION
        rule.hasCommitment -> Why.COMMITMENT
        rule.hasBudget -> Why.BUDGET
        rule.isScheduled -> Why.SCHEDULE
        else -> Why.ALWAYS
    }

    /**
     * True if a DNS block of [qname] under [rule] is worth asking the
     * accessibility service about. Only the site's user-facing hostnames (bare,
     * `www.`, `m.`, …) count: SDK/API subdomains are other apps' background
     * traffic, never a person opening the site. Screen off means nobody is
     * looking, so nothing to show.
     */
    fun shouldSignal(qname: String, rule: BlockRule, screenOn: Boolean): Boolean =
        screenOn && !rule.hasPathExceptions && Blocklist.isFrontDoor(qname, rule.domain)

    /**
     * True if the address bar's [host] is [domain] or one of its subdomains.
     * Required before showing the screen: browsers prefetch DNS for search
     * suggestions while the user types, so a blocked lookup alone does not mean
     * the blocked site is what's on screen.
     */
    fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * Per-domain rate limit. One page load asks for A and AAAA, often for both
     * the bare and `www.` names, and retries; without this each would re-open
     * the screen. Thread-safe: the DNS reader thread and the main thread both
     * call it.
     */
    class Cooldown(private val millis: Long) {
        private val last = HashMap<String, Long>()

        @Synchronized
        fun accept(domain: String, nowMillis: Long): Boolean {
            val prev = last[domain]
            if (prev != null && nowMillis - prev in 0 until millis) return false
            last[domain] = nowMillis
            if (last.size > 64) last.entries.removeIf { nowMillis - it.value >= millis }
            return true
        }
    }
}
