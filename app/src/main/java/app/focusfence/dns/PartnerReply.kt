package app.focusfence.dns

/**
 * Plan 12, later list: a partner answers with one tap from their report page, and the owner's phone shows it. Set
 * replies only, so nothing personal is written on the server (Logan's "random things to add" list, built 2026-11-25).
 * Compiled by app and server.
 */
object PartnerReply {
    enum class Reply(val key: String, val text: String) {
        ALL_GOOD("all_good", "All good"),
        TALK("talk", "Let's talk"),
        CALL("call", "Call me when you can"),
        PROUD("proud", "Proud of you"),
        ;

        companion object {
            fun fromKey(k: String?): Reply? = values().firstOrNull { it.key == k }
        }
    }

    /** What the owner reads: "Sam: Let's talk". */
    fun line(by: String, r: Reply): String = "${by.ifBlank { "Your partner" }}: ${r.text}"

    /** One reply per partner per this long, so a held button can't flood the owner's phone. */
    const val EVERY_MS: Long = 2 * 60_000L
}
