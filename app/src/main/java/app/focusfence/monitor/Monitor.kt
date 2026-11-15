package app.focusfence.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.focusfence.UiUtil
import app.focusfence.data.EventLog
import app.focusfence.data.MonitorSettings
import app.focusfence.dns.BatteryPolicy
import app.focusfence.dns.BatterySample
import app.focusfence.dns.LogEvents
import app.focusfence.dns.PerfWindow
import app.focusfence.vpn.DnsVpnService
import java.util.concurrent.Executors

/**
 * Process-wide measurement and integrity watcher, active while either service
 * runs (they [attach]/[detach]).
 *
 * - **Battery/CPU**: samples on ACTION_BATTERY_CHANGED and screen on/off only
 *   — broadcasts the system sends anyway — so measuring adds no timer wakeups.
 *   [BatteryPolicy] throttles the noisy battery broadcast.
 * - **Perf**: services count into [perf]; each sample closes the window and
 *   writes it to diag, so perf windows line up with battery samples.
 * - **Integrity**: compares the state of every protection component with its
 *   last known value and writes each change to the *activity* log, flushed
 *   immediately. Turning something off must never be silent.
 * - **App timeline**: piggybacks a UsageStats pull on each sample.
 *
 * Known gap: if every service is stopped and the app isn't opened, nothing
 * runs to notice further changes; the next check (app start, service start)
 * reports them with the time it noticed, not the time they happened. Closing
 * that gap needs a server-side heartbeat (docs/specs/accountability-architecture.md).
 */
object Monitor {

    private const val TAG = "FocusFenceMonitor"
    private const val KEY_INTEGRITY_PREFIX = "integ_"
    private const val STARTUP_GRACE_MS = 20_000L

    val perf = PerfWindow(startedAt = System.currentTimeMillis())

    /** Set by UrlFilterService while the system has it bound. */
    @Volatile
    var a11yConnected = false

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "focusfence-monitor").apply { isDaemon = true }
    }

    private var appContext: Context? = null
    private val attached = HashSet<String>()

    // Worker-thread state.
    private var lastSample: BatterySample? = null

    @Volatile
    private var screenOn = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> worker.execute { onBattery(context, intent) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    screenOn = intent.action == Intent.ACTION_SCREEN_ON
                    worker.execute { onBattery(context, stickyBattery(context)) }
                }
            }
        }
    }

    /** [who] is a short component name ("vpn", "a11y"); attaching twice is a no-op. */
    @Synchronized
    fun attach(context: Context, who: String) {
        val app = context.applicationContext
        appContext = app
        if (!attached.add(who) || attached.size > 1) {
            checkIntegrityAsync(app)
            return
        }
        screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts are delivered to non-exported receivers; the flag is
        // required for context-registered receivers on Android 14+.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        checkIntegrityAsync(app)
    }

    @Synchronized
    fun detach(who: String) {
        if (!attached.remove(who)) return
        val app = appContext ?: return
        if (attached.isEmpty()) {
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Not registered; nothing to undo.
            }
            worker.execute { closePerfWindow() }
        }
        checkIntegrityAsync(app)
    }

    fun checkIntegrityAsync(context: Context) {
        val app = context.applicationContext
        worker.execute { checkIntegrity(app) }
    }

    /** For the diagnostics screen and export: integrity check + timeline pull, then wait. */
    fun refreshBlocking(context: Context) {
        val app = context.applicationContext
        worker.submit {
            checkIntegrity(app)
            safely("usage pull") { UsageCollector.pull(app) }
        }.get()
    }

    /** A null receiver just returns the current sticky battery intent. */
    private fun stickyBattery(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun onBattery(context: Context, intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val now = System.currentTimeMillis()
        if (!BatteryPolicy.shouldSample(lastSample, pct, plugged, screenOn, now)) return

        val bm = context.getSystemService(BatteryManager::class.java)
        val sample = BatterySample(
            t = now,
            levelPct = pct,
            plugged = plugged,
            screenOn = screenOn,
            cpuMs = Process.getElapsedCpuTime(),
            uptimeMs = SystemClock.elapsedRealtime(),
            pid = Process.myPid(),
            // Unsupported properties read as Long.MIN_VALUE (or 0 on some OEMs).
            chargeUah = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it > 0 && it != Long.MIN_VALUE },
            currentUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Long.MIN_VALUE && it != 0L },
            tempDeciC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE },
        )
        lastSample = sample
        EventLog.diag(LogEvents.battery(now, sample))
        closePerfWindow()
        checkIntegrity(context)
        safely("usage pull") { UsageCollector.pull(context) }
    }

    private fun closePerfWindow() {
        val dropped = EventLog.takeDropped()
        if (dropped > 0) perf.count("log_dropped", dropped)
        if (perf.isEmpty()) return
        val now = System.currentTimeMillis()
        val (window, fields) = perf.snapshot(now)
        EventLog.diag(LogEvents.perf(now, window, fields))
    }

    /** Current value of every protection component we can observe. */
    private fun integrityState(context: Context): Map<String, String> {
        val state = LinkedHashMap<String, String>()
        // Right after a process start the services haven't re-bound yet, so
        // "stopped"/"unbound" would be a false alarm on every restart. They
        // log their own start/stop; the next check after the grace period
        // catches anything that genuinely failed to come back.
        val settled = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() >= STARTUP_GRACE_MS
        if (settled) {
            state["vpn"] = if (DnsVpnService.isRunning) "running" else "stopped"
            // Only knowable while the VPN runs; omitted (not "unknown") when stopped
            // so every stop doesn't log a second, meaningless change.
            if (DnsVpnService.isRunning) state["vpn_always_on"] = DnsVpnService.alwaysOnState
            state["a11y_bound"] = a11yConnected.toString()
        }
        state["a11y_enabled"] = UiUtil.isUrlFilterEnabled(context).toString()
        state["usage_access"] = UsageCollector.hasAccess(context).toString()
        // Capture state in the integrity trail: turning monitoring on or off is
        // itself an accountability event.
        state["capture_screenshots"] = MonitorSettings.screenshotsEnabled(context).toString()
        state["capture_text"] = MonitorSettings.textEnabled(context).toString()
        // Private DNS carries lookups around the filter. The key is not public
        // API; some builds refuse to expose it, which we record as "unknown".
        state["private_dns"] = try {
            Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "default"
        } catch (e: SecurityException) {
            "unknown"
        }
        return state
    }

    private fun checkIntegrity(context: Context) {
        safely("integrity") {
            val prefs = EventLog.prefs()
            val editor = prefs.edit()
            var changed = false
            val now = System.currentTimeMillis()
            for ((key, value) in integrityState(context)) {
                val prefKey = KEY_INTEGRITY_PREFIX + key
                val old = prefs.getString(prefKey, null)
                if (old == value) continue
                EventLog.activity(LogEvents.integrity(now, key, value, old), urgent = true)
                editor.putString(prefKey, value)
                changed = true
            }
            if (changed) editor.apply()
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, what, e)
            EventLog.diag(LogEvents.error(System.currentTimeMillis(), "monitor", what, e.toString()))
        }
    }
}
