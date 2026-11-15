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
import app.focusfence.accessibility.UrlFilterService
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.data.MonitorSettings
import app.focusfence.data.StrictStore
import app.focusfence.dns.StrictLock
import app.focusfence.vpn.DnsVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    fun chooseWatchedApps(context: Context, onDone: () -> Unit) {
        val launchables = launchableApps(context)
        if (launchables.isEmpty()) return
        val current = MonitorSettings.watchedApps(context)
        val labels = launchables.map { it.second }.toTypedArray()
        val checked = BooleanArray(launchables.size) { launchables[it].first in current }
        val original = checked.copyOf()
        val locked = StrictStore.isLocked(context)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watched_dialog_title)
            .setMultiChoiceItems(labels, checked) { dialog, index, isChecked ->
                // Removing an app from monitoring is the loosening direction.
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
                MonitorSettings.setWatchedApps(context, selected)
                if (selected.isNotEmpty() && !isUrlFilterEnabled(context)) promptEnableUrlFilter(context)
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
