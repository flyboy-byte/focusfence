package app.focusfence

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.focusfence.accessibility.UrlFilterService
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.data.StrictStore
import app.focusfence.dns.BlockRule
import app.focusfence.dns.StrictLock
import app.focusfence.vpn.DnsVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI helpers shared between the tab fragments and the domain editor. */
object UiUtil {

    fun isUrlFilterEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val pkg = context.packageName
        val component = "$pkg/${UrlFilterService::class.java.name}"
        val short = "$pkg/.accessibility.UrlFilterService"
        return enabled.split(':').any { it.equals(component, true) || it.equals(short, true) }
    }

    fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    fun showStrictLockedDialog(context: Context, onChanged: (() -> Unit)? = null) {
        val state = StrictStore.state(context)
        val message = if (state is StrictLock.State.Pending) {
            context.getString(R.string.strict_dialog_pending, formatCountdown(state.remainingMs))
        } else {
            context.getString(R.string.strict_dialog_locked)
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.strict_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.ok, null)
        if (state is StrictLock.State.Locked) {
            builder.setPositiveButton(R.string.strict_request_unlock) { _, _ ->
                StrictStore.requestUnlock(context)
                onChanged?.invoke()
            }
        }
        builder.show()
    }

    fun promptEnableUrlFilter(context: Context, onDismiss: (() -> Unit)? = null) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.section_service_label)
            .setMessage(R.string.section_enable_message)
            .setPositiveButton(R.string.section_enable_button) { _, _ ->
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    /** Launchable apps as (packageName, label), excluding ourselves and Settings. */
    fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName && it.first != "com.android.settings" }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun chooseBlockedApps(context: Context, onDone: () -> Unit) {
        val launchables = launchableApps(context)
        if (launchables.isEmpty()) return
        val current = BlockedAppsStore.get(context)
        val labels = launchables.map { it.second }.toTypedArray()
        val checked = BooleanArray(launchables.size) { launchables[it].first in current }
        val original = checked.copyOf()
        val locked = StrictStore.isLocked(context)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.apps_dialog_title)
            .setMultiChoiceItems(labels, checked) { dialog, index, isChecked ->
                // Strict mode: un-blocking an app is a loosening change.
                if (locked && !isChecked && original[index]) {
                    checked[index] = true
                    (dialog as AlertDialog).listView.setItemChecked(index, true)
                    Toast.makeText(context, R.string.strict_locked_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = launchables.indices
                    .filter { checked[it] }
                    .map { launchables[it].first }
                    .toSet()
                BlockedAppsStore.set(context, selected)
                if (selected.isNotEmpty() && !isUrlFilterEnabled(context)) {
                    promptEnableUrlFilter(context)
                }
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Per-app rule editor: always / daily window / until a chosen instant. Mirrors
     * the mode choice in [DomainDetailActivity] rather than inventing a new pattern.
     */
    fun editAppRule(activity: FragmentActivity, pkg: String, onDone: () -> Unit) {
        val existing = BlockedAppsStore.rulesByPackage(activity)[pkg]
        val options = arrayOf<CharSequence>(
            activity.getString(R.string.app_schedule_always),
            activity.getString(R.string.app_schedule_window),
            activity.getString(R.string.app_schedule_until),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.app_rule_dialog_title, appLabel(activity, pkg)))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> commitAppRule(activity, BlockRule(pkg), existing, onDone)
                    1 -> pickTime(
                        activity,
                        R.string.app_window_start,
                        existing?.startMinute?.takeIf { it != BlockRule.ALWAYS } ?: (9 * 60),
                    ) { start ->
                        pickTime(
                            activity,
                            R.string.app_window_end,
                            existing?.endMinute?.takeIf { it != BlockRule.ALWAYS } ?: (18 * 60),
                        ) { end ->
                            commitAppRule(activity, BlockRule(pkg, start, end), existing, onDone)
                        }
                    }
                    2 -> pickTime(activity, R.string.app_until_title, 18 * 60) { minute ->
                        commitAppRule(
                            activity,
                            BlockRule(pkg, commitUntilEpochMillis = nextOccurrenceOf(minute)),
                            existing,
                            onDone,
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The next wall-clock occurrence of [minuteOfDay] — today if still ahead, else tomorrow. */
    private fun nextOccurrenceOf(minuteOfDay: Int): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun commitAppRule(
        context: Context,
        proposed: BlockRule,
        existing: BlockRule?,
        onDone: () -> Unit,
    ) {
        if (StrictStore.isLocked(context) && isLoosening(existing, proposed)) {
            showStrictLockedDialog(context)
            return
        }
        BlockedAppsStore.upsert(context, proposed)
        onDone()
    }

    /**
     * Strict mode guards *loosening*, so this is deliberately conservative:
     * always-block is the strictest state and is never refused, and a commitment
     * may be extended but not cut short. Anything else that changes an existing
     * rule is treated as a loosening, because two arbitrary daily windows have no
     * meaningful "stricter than" ordering and guessing wrong would defeat a
     * boundary the user set precisely so their later self couldn't move it.
     */
    private fun isLoosening(existing: BlockRule?, proposed: BlockRule): Boolean {
        if (existing == null) return false
        val proposedIsAlways =
            !proposed.isScheduled && !proposed.hasCommitment && !proposed.hasBudget
        if (proposedIsAlways) return false
        if (existing.hasCommitment) {
            return !proposed.hasCommitment ||
                proposed.commitUntilEpochMillis < existing.commitUntilEpochMillis
        }
        return true
    }

    /** One-line description of an app rule for the blocklist row. */
    fun appRuleSummary(context: Context, rule: BlockRule): String = when {
        rule.hasCommitment -> {
            val until = Instant.ofEpochMilli(rule.commitUntilEpochMillis)
                .atZone(ZoneId.systemDefault())
            if (rule.commitUntilEpochMillis <= System.currentTimeMillis()) {
                context.getString(R.string.app_rule_until_passed)
            } else {
                context.getString(
                    R.string.app_rule_until,
                    until.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())),
                )
            }
        }
        // Apps cannot have budgets until W10 lands; say so rather than implying a block.
        rule.hasBudget -> context.getString(R.string.app_rule_budget_unsupported)
        rule.isScheduled -> context.getString(
            R.string.app_rule_window,
            formatMinuteOfDay(rule.startMinute),
            formatMinuteOfDay(rule.endMinute),
        )
        else -> context.getString(R.string.app_rule_always)
    }

    private fun formatMinuteOfDay(m: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    private fun pickTime(
        activity: FragmentActivity,
        titleRes: Int,
        defaultMinute: Int,
        onPicked: (Int) -> Unit,
    ) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(defaultMinute / 60)
            .setMinute(defaultMinute % 60)
            .setTitleText(titleRes)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(activity.supportFragmentManager, "app_rule_time")
    }

    fun chooseExcludedApps(context: Context, onDone: () -> Unit) {
        val launchables = launchableApps(context)
        if (launchables.isEmpty()) return
        val current = ExcludedAppsStore.get(context)
        val labels = launchables.map { it.second }.toTypedArray()
        val checked = BooleanArray(launchables.size) { launchables[it].first in current }
        val original = checked.copyOf()
        val locked = StrictStore.isLocked(context)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.excluded_dialog_title)
            .setMultiChoiceItems(labels, checked) { dialog, index, isChecked ->
                // Strict mode: excluding an app is a filter bypass (loosening).
                if (locked && isChecked && !original[index]) {
                    checked[index] = false
                    (dialog as AlertDialog).listView.setItemChecked(index, false)
                    Toast.makeText(context, R.string.strict_locked_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = launchables.indices
                    .filter { checked[it] }
                    .map { launchables[it].first }
                    .toSet()
                ExcludedAppsStore.set(context, selected)
                restartVpnIfRunning(context)
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Exclusion changes only take effect on a fresh tunnel. */
    fun restartVpnIfRunning(context: Context) {
        if (!DnsVpnService.isRunning) return
        val appContext = context.applicationContext
        appContext.startService(
            Intent(appContext, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        )
        Handler(Looper.getMainLooper()).postDelayed({
            if (VpnService.prepare(appContext) == null) {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, DnsVpnService::class.java)
                        .setAction(DnsVpnService.ACTION_START),
                )
            }
        }, 600)
    }
}
