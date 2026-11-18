package app.focusfence.dns

/**
 * "Is screenshot capture actually working?" in one line, for the Monitoring
 * screen. Captured images are never shown on the phone (flagged ones are
 * encrypted to the partner), so without this the owner can't tell a working
 * pipeline from a silently blocked one. Pure, so every branch is tested.
 *
 * Blockers are reported in the order a user would fix them; once nothing is
 * blocking, the last capture and its AI verdict are shown.
 */
object CaptureStatus {

    data class Inputs(
        val screenshotsOn: Boolean,
        val supported: Boolean,
        val accessibilityOn: Boolean,
        /** True in "all apps" mode; then [watchedApps] (chosen count) doesn't matter. */
        val watchAll: Boolean,
        val watchedApps: Int,
        val aiOn: Boolean,
        val partnerKeySet: Boolean,
        val lastShotAt: Long,
        val lastPkg: String?,
        val lastLabel: String?,
        val lastScore: Double?,
        val lastFlagged: Boolean,
        val taken: Long,
        val flagged: Long,
        val failed: Long,
        val aiLoadFailed: Boolean,
    )

    enum class Level { OFF, BLOCKED, WAITING, WORKING, WARNING }

    data class Status(val level: Level, val text: String)

    fun describe(i: Inputs, now: Long, ago: (Long) -> String): Status {
        if (!i.supported) return Status(Level.BLOCKED, "Screenshots need Android 11 or newer.")
        if (!i.screenshotsOn) return Status(Level.OFF, "Screenshots are off.")
        if (!i.accessibilityOn) return Status(Level.BLOCKED, "Not capturing: turn on Focus Fence's accessibility service (Settings → Accessibility).")
        if (!i.watchAll && i.watchedApps == 0) return Status(Level.BLOCKED, "Not capturing: choose at least one app to watch.")
        if (i.lastShotAt == 0L && i.failed > 0) {
            return Status(
                Level.WARNING,
                "Capture is trying but failing (${i.failed} attempts). Android may be refusing screenshots " +
                    "for the watched app (some apps block them), or the accessibility service needs restarting.",
            )
        }
        if (i.lastShotAt == 0L) {
            return Status(
                Level.WAITING,
                "Ready. Nothing captured yet — open a watched app and use it for a minute or two " +
                    "(the first capture comes after a random delay).",
            )
        }
        val sb = StringBuilder("Last screenshot ${ago(i.lastShotAt)}")
        if (i.lastPkg != null) sb.append(" in ").append(i.lastPkg)
        sb.append(". ")
        when {
            !i.aiOn -> sb.append("AI flagging is off, so it wasn't scored.")
            i.aiLoadFailed -> sb.append("AI model failed to load, so it wasn't scored.")
            i.lastLabel == null -> sb.append("Not scored.")
            else -> {
                sb.append("AI: ${i.lastLabel} ${pct(i.lastScore)}")
                if (i.lastFlagged) sb.append(" — flagged")
                sb.append('.')
            }
        }
        sb.append("\nTotal: ${i.taken} taken, ${i.flagged} flagged")
        if (i.failed > 0) sb.append(", ${i.failed} failed")
        sb.append('.')
        val warn = (i.aiOn && i.aiLoadFailed) || (i.flagged > 0 && !i.partnerKeySet) || (i.failed > 0 && i.failed >= i.taken)
        if (i.flagged > 0 && !i.partnerKeySet) {
            sb.append("\nFlagged images aren't being kept: no partner key is set, so there's no one to encrypt them to.")
        }
        return Status(if (warn) Level.WARNING else Level.WORKING, sb.toString())
    }

    private fun pct(score: Double?): String = if (score == null) "" else "(${(score * 100).toInt()}%)"
}
