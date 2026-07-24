package app.focusfence

/** The guide pages, shared by the first-launch onboarding wizard and the Help screen. */
object HelpContent {
    data class Page(val titleRes: Int, val bodyRes: Int)

    val PAGES = listOf(
        Page(R.string.help_how_title, R.string.help_how_body),
        Page(R.string.help_rules_title, R.string.help_rules_body),
        Page(R.string.help_sections_title, R.string.help_sections_body),
        Page(R.string.help_limits_title, R.string.help_limits_body),
        Page(R.string.help_start_title, R.string.help_start_body),
    )
}
