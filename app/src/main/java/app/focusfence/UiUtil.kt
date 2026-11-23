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
import app.focusfence.data.PinStore
import app.focusfence.data.StrictStore
import app.focusfence.dns.GuardPolicy
import app.focusfence.dns.StrictLock
import app.focusfence.dns.WatchScope
import app.focusfence.monitor.AppCatalog
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
        // With a PIN configured, the correct PIN unlocks immediately; the timed
        // delay remains as a fallback so a forgotten PIN can't lock you out.
        if (state is StrictLock.State.Locked) {
            when (PinStore.mode(context)) {
                PinStore.UnlockMode.OWNER_PIN ->
                    return showPinDialog(context, R.string.strict_pin_owner_prompt,
                        { PinStore.verifyOwner(context, it) },
                        onVerified = {
                            StrictStore.unlockNow(context)
                            Toast.makeText(context, R.string.strict_pin_ok, Toast.LENGTH_SHORT).show()
                            onChanged?.invoke()
                        },
                        timerFallback = { StrictStore.requestUnlock(context); onChanged?.invoke() })
                PinStore.UnlockMode.PARTNER_PIN ->
                    return showPinDialog(context, R.string.strict_pin_partner_prompt,
                        { PinStore.verifyPartner(context, it) },
                        onVerified = {
                            StrictStore.unlockNow(context)
                            Toast.makeText(context, R.string.strict_pin_ok, Toast.LENGTH_SHORT).show()
                            onChanged?.invoke()
                        },
                        timerFallback = { StrictStore.requestUnlock(context); onChanged?.invoke() })
                PinStore.UnlockMode.TIMER -> Unit
            }
        }
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

    private fun showPinDialog(
        context: Context,
        promptRes: Int,
        verify: (String) -> Boolean,
        onVerified: () -> Unit,
        timerFallback: () -> Unit,
        timerLabel: Int = R.string.strict_pin_use_timer,
    ) {
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = context.getString(R.string.strict_pin_hint)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.strict_title)
            .setMessage(promptRes)
            .setView(input)
            .setPositiveButton(R.string.strict_pin_unlock) { _, _ ->
                if (verify(input.text?.toString().orEmpty())) {
                    onVerified()
                } else {
                    Toast.makeText(context, R.string.strict_pin_wrong, Toast.LENGTH_LONG).show()
                }
            }
            // Fallback so a lost PIN can't permanently lock the user out.
            .setNeutralButton(timerLabel) { _, _ -> timerFallback() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Runs [onAllowed] if [action]'s friction is satisfied, otherwise prompts
     * for it: a PIN level prompts the owner/partner PIN *now* (and on success
     * runs the action without opening the global unlock window, so unlocking a
     * weaker action never grants this one); a delay level routes through the
     * timed strict dialog and the owner retries once the window opens. Call
     * this at every loosening site in place of a bare strict-lock check.
     */
    fun guard(context: Context, action: app.focusfence.dns.GuardPolicy.Action, onAllowed: () -> Unit) {
        when (val req = app.focusfence.data.GuardStore.requirement(context, action)) {
            is app.focusfence.dns.GuardPolicy.Requirement.Allowed -> onAllowed()
            is app.focusfence.dns.GuardPolicy.Requirement.TimedUnlock ->
                // A paired partner can skip the wait with the code shown in their app (checked offline); the wait is
                // always still there, so a partner who is asleep or gone never locks the owner out.
                if (PinStore.hasPartnerPin(context) && StrictStore.state(context) is StrictLock.State.Locked) {
                    showPinDialog(
                        context, R.string.strict_wait_or_partner_prompt, { PinStore.verifyPartner(context, it) },
                        onVerified = {
                            Toast.makeText(context, R.string.strict_pin_ok, Toast.LENGTH_SHORT).show()
                            onAllowed()
                        },
                        timerFallback = { StrictStore.requestUnlock(context); showStrictLockedDialog(context) },
                        timerLabel = R.string.strict_wait_instead,
                    )
                } else showStrictLockedDialog(context)
            is app.focusfence.dns.GuardPolicy.Requirement.Pin -> {
                val prompt = if (req.partner) R.string.strict_pin_partner_prompt else R.string.strict_pin_owner_prompt
                val verify: (String) -> Boolean = if (req.partner) {
                    { PinStore.verifyPartner(context, it) }
                } else {
                    { PinStore.verifyOwner(context, it) }
                }
                showPinDialog(
                    context, prompt, verify,
                    onVerified = {
                        Toast.makeText(context, R.string.strict_pin_ok, Toast.LENGTH_SHORT).show()
                        onAllowed()
                    },
                    // Lost-PIN fallback: open the timed path instead of this action.
                    timerFallback = { StrictStore.requestUnlock(context); showStrictLockedDialog(context) },
                )
            }
        }
    }

    fun promptEnableUrlFilter(context: Context, onDismiss: (() -> Unit)? = null) {
        // Android 13+ greys out accessibility for sideloaded apps until "Allow
        // restricted settings" is tapped in App info — the usual reason the
        // switch "doesn't work". Say so up front and offer the shortcut.
        val restricted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val message = context.getString(R.string.section_enable_message) +
            if (restricted) "\n\n" + context.getString(R.string.restricted_settings_hint) else ""
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.section_service_label)
            .setMessage(message)
            .setPositiveButton(R.string.section_enable_button) { _, _ ->
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (restricted) {
                    setNeutralButton(R.string.open_app_info) { _, _ ->
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                        )
                    }
                }
            }
            .create()
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    fun isBatteryExempt(context: Context): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /**
     * Asks Android to stop battery-optimizing Focus Fence (system confirmation
     * dialog), with a maker-specific tip first: Samsung, Xiaomi and others add
     * their own app killers on top of Android's, which the system dialog doesn't
     * cover.
     */
    fun requestBatteryExemption(context: Context) {
        val tip = oemBatteryTip()
        val go = {
            val ok = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + context.packageName))
                )
            }.isSuccess
            if (!ok) runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.battery_exempt_title)
            .setMessage(context.getString(R.string.battery_exempt_body) + (tip?.let { "\n\n" + context.getString(it) } ?: ""))
            .setPositiveButton(R.string.battery_exempt_go) { _, _ -> go() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun oemBatteryTip(): Int? = when (android.os.Build.MANUFACTURER.lowercase(Locale.ROOT)) {
        "samsung" -> R.string.oem_tip_samsung
        "xiaomi", "redmi", "poco" -> R.string.oem_tip_xiaomi
        "oneplus", "oppo", "realme" -> R.string.oem_tip_oppo
        "huawei", "honor" -> R.string.oem_tip_huawei
        "vivo" -> R.string.oem_tip_vivo
        else -> null
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
        val pm = context.packageManager
        val browsers = app.focusfence.monitor.AppCatalog.browsers(context)
        // (package, label, risk) — risk decides whether excluding needs friction.
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
            .map { ai ->
                val isBrowser = ai.packageName in browsers
                val risk = app.focusfence.dns.ExclusionPolicy.classify(
                    ai.packageName, ai.category, isBrowser,
                    app.focusfence.dns.WatchScope.group(ai.packageName, ai.category, isBrowser),
                )
                Triple(ai.packageName, pm.getApplicationLabel(ai).toString(), risk)
            }
            // Instant-toggle apps first: that's what people open this list for.
            .sortedWith(compareBy<Triple<String, String, app.focusfence.dns.ExclusionPolicy.Risk>> { it.third.ordinal }
                .thenBy { it.second.lowercase(Locale.ROOT) })
        if (apps.isEmpty()) return
        val current = ExcludedAppsStore.get(context)
        val labels = apps.map {
            if (it.third == app.focusfence.dns.ExclusionPolicy.Risk.GUARDED) {
                context.getString(R.string.excluded_guarded_suffix, it.second)
            } else it.second
        }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in current }
        val riskOf = apps.associate { it.first to it.third }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.excluded_dialog_title)
            .setMultiChoiceItems(labels, checked, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = apps.indices.filter { checked[it] }.map { apps[it].first }.toSet()
                val guarded = app.focusfence.dns.ExclusionPolicy.needsGuard(current, selected) {
                    riskOf[it] ?: app.focusfence.dns.ExclusionPolicy.Risk.GUARDED
                }
                // Removals and free apps apply now; only newly added guarded apps wait.
                ExcludedAppsStore.set(context, selected - guarded)
                restartVpnIfRunning(context)
                if (guarded.isEmpty()) {
                    onDone()
                } else {
                    guard(context, app.focusfence.dns.GuardPolicy.Action.EXCLUDE_APP) {
                        ExcludedAppsStore.set(context, selected)
                        restartVpnIfRunning(context)
                        onDone()
                    }
                    onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * What capture watches: all apps (default), apps picked by type (auto-sorted),
     * individual apps, or exclusions from "all". Narrowing what's watched is the
     * loosening direction, so it goes through the capture friction guard.
     */
    fun chooseWatchScope(context: Context, onDone: () -> Unit) {
        val options = arrayOf(
            context.getString(R.string.watch_all),
            context.getString(R.string.watch_by_type),
            context.getString(R.string.watch_pick),
            context.getString(R.string.watch_exclude),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watched_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        MonitorSettings.setWatch(context, WatchScope.Mode.ALL, MonitorSettings.watchedApps(context))
                        afterWatchChange(context, onDone)
                    }
                    1 -> pickByType(context, onDone)
                    2 -> pickApps(context, onDone)
                    3 -> pickExclusions(context, onDone)
                }
            }
            .show()
    }

    private fun afterWatchChange(context: Context, onDone: () -> Unit) {
        if (!isUrlFilterEnabled(context)) promptEnableUrlFilter(context)
        onDone()
    }

    /** Narrowing (fewer apps watched than now) needs the capture guard. */
    private fun applyNarrowing(context: Context, narrows: Boolean, apply: () -> Unit, onDone: () -> Unit) {
        if (narrows) {
            guard(context, GuardPolicy.Action.DISABLE_CAPTURE) { apply(); afterWatchChange(context, onDone) }
        } else {
            apply(); afterWatchChange(context, onDone)
        }
    }

    private fun pickByType(context: Context, onDone: () -> Unit) {
        val apps = AppCatalog.apps(context)
        val groups = WatchScope.Group.values().filter { g -> apps.any { it.group == g } }
        val labels = groups.map { g -> "${g.label} (${apps.count { it.group == g }})" }.toTypedArray()
        val checked = BooleanArray(groups.size) { groups[it] != WatchScope.Group.OTHER }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watch_by_type)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosenGroups = groups.filterIndexed { i, _ -> checked[i] }.toSet()
                val selected = apps.filter { it.group in chosenGroups }.map { it.pkg }.toSet()
                val narrows = MonitorSettings.watchMode(context) == WatchScope.Mode.ALL ||
                    !selected.containsAll(MonitorSettings.watchedApps(context))
                applyNarrowing(context, narrows, { MonitorSettings.setWatch(context, WatchScope.Mode.CHOSEN, selected) }, onDone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickApps(context: Context, onDone: () -> Unit) {
        val apps = AppCatalog.apps(context)
        if (apps.isEmpty()) return
        val wasAll = MonitorSettings.watchMode(context) == WatchScope.Mode.ALL
        val current = MonitorSettings.watchedApps(context)
        // Coming from "all", start with everything ticked so unticking is explicit.
        val checked = BooleanArray(apps.size) { wasAll || apps[it].pkg in current }
        val labels = apps.map { "${it.label}  ·  ${it.group.label}" }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watch_pick)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = apps.indices.filter { checked[it] }.map { apps[it].pkg }.toSet()
                val narrows = wasAll || !selected.containsAll(current)
                applyNarrowing(context, narrows, { MonitorSettings.setWatch(context, WatchScope.Mode.CHOSEN, selected) }, onDone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickExclusions(context: Context, onDone: () -> Unit) {
        val apps = AppCatalog.apps(context)
        if (apps.isEmpty()) return
        val current = MonitorSettings.excludedApps(context)
        val checked = BooleanArray(apps.size) { apps[it].pkg in current }
        val labels = apps.map { "${it.label}  ·  ${it.group.label}" }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watch_exclude)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val excluded = apps.indices.filter { checked[it] }.map { apps[it].pkg }.toSet()
                // Excluding more is narrowing; switching to ALL widens or keeps scope.
                val narrows = !current.containsAll(excluded)
                applyNarrowing(context, narrows, {
                    MonitorSettings.setExcludedApps(context, excluded)
                    MonitorSettings.setWatch(context, WatchScope.Mode.ALL, MonitorSettings.watchedApps(context))
                }, onDone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun stopVpn(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        )
    }

    /** Starts the tunnel if VPN consent is already granted; returns false if consent is needed. */
    fun startVpnIfPrepared(context: Context): Boolean {
        val appContext = context.applicationContext
        if (VpnService.prepare(appContext) != null) return false
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START),
        )
        return true
    }

    /** Exclusion changes only take effect on a fresh tunnel. */
    fun restartVpnIfRunning(context: Context) {
        if (!DnsVpnService.isRunning) return
        val appContext = context.applicationContext
        appContext.startService(
            Intent(appContext, DnsVpnService::class.java)
                .setAction(DnsVpnService.ACTION_STOP)
                .putExtra(DnsVpnService.EXTRA_RESTART, true)
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
