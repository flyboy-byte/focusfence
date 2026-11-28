package app.focusfence.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import app.focusfence.MainActivity
import app.focusfence.R
import app.focusfence.data.AdListStore
import app.focusfence.data.AllowlistStore
import app.focusfence.data.BlockScreenSettings
import app.focusfence.data.BlocklistStore
import app.focusfence.data.CategoryStore
import app.focusfence.data.EventLog
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.data.LogSettings
import app.focusfence.data.StatsStore
import app.focusfence.data.StrictStore
import app.focusfence.data.UsageStore
import app.focusfence.dns.AlwaysOnNudge
import app.focusfence.dns.StatsCodec
import app.focusfence.dns.BlockRule
import app.focusfence.dns.BlockScreen
import app.focusfence.dns.BlockTriage
import app.focusfence.dns.Blocklist
import app.focusfence.dns.Categories
import app.focusfence.dns.Dns
import app.focusfence.dns.LogEvents
import app.focusfence.dns.Packets
import app.focusfence.dns.RuleEvaluator
import app.focusfence.dns.UsageTracker
import app.focusfence.dns.VpnHealth
import app.focusfence.dns.Upstreams
import app.focusfence.data.DnsUpstreamStore
import app.focusfence.monitor.Monitor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A DNS-only filtering VPN.
 *
 * The tunnel is configured so that ONLY packets addressed to the tunnel's virtual
 * DNS servers are routed into it — all other traffic takes the normal network path,
 * which is why the architecture is cheap to run (measured, not assumed, by the
 * diag log — see docs/specs/event-log.md). Queries for blocked domains get a
 * locally synthesized cacheable 0.0.0.0/:: answer (see [Dns.buildBlockedResponse]);
 * everything else is relayed to a real resolver over a protected socket.
 *
 * Every query is also a detection event: decisions go to the activity log
 * (allowed ones too, unless the owner turns that off), and resolver health
 * goes to [Monitor.perf] for the diag log.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "FocusFence"

        const val ACTION_START = "app.focusfence.action.START"
        const val ACTION_STOP = "app.focusfence.action.STOP"
        const val ACTION_STATE_CHANGED = "app.focusfence.action.STATE_CHANGED"
        /** On ACTION_STOP: a stop-then-start restart, not the user turning protection off. */
        const val EXTRA_RESTART = "restart"

        private const val VPN4_ADDRESS = "10.111.222.1"
        private const val VPN4_DNS = "10.111.222.3"
        private const val VPN6_ADDRESS = "fd66:f0cf:0:1::1"
        private const val VPN6_DNS = "fd66:f0cf:0:1::53"

        private const val MTU = 8192
        private const val NOTIFICATION_CHANNEL = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_CHANNEL = "vpn_alert"
        private const val ALERT_ID = 2
        private const val NUDGE_ID = 3
        /** Health is re-evaluated at most this often from the relay path. */
        private const val HEALTH_EVAL_INTERVAL_MS = 5_000L

        private const val UPSTREAM_TIMEOUT_MS = 2500
        /** Start the next resolver beside a slow one after this long (Automatic mode only). */
        private const val HEDGE_MS = 600L
        /** Threads for hedged attempts only; separate from the 12 query workers so a burst cannot starve them. */
        private val hedgePool: java.util.concurrent.Executor = java.util.concurrent.ThreadPoolExecutor(
            0, 48, 10, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
            { r -> Thread(r, "focusfence-hedge").apply { isDaemon = true } },
            // Full (a dead network and a burst of lookups): run the attempt on the calling worker, i.e. fall back to the
            // old one-at-a-time behaviour rather than grow without bound or drop the query.
            java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy(),
        )
        /** The network's own resolver is close; give up on it fast and fail over. */
        private const val UDP_TIMEOUT_MS = 1500
        /** How long a looked-up underlying network + its DNS servers is trusted. */
        private const val UNDERLYING_TTL_MS = 15_000L
        /** Ad/tracker blocks are stable — let clients cache them for an hour. */
        private const val AD_BLOCK_TTL_SECONDS = 3600
        /** User rules change with schedules/budgets — keep their cache short. */
        private const val RULE_BLOCK_TTL_SECONDS = 60

        @Volatile
        var isRunning = false
            private set

        val blockedCount = AtomicLong(0)

        /** "on", "lockdown", "off", or "unknown" (pre-Android 10 / not running). */
        @Volatile
        var alwaysOnState = "unknown"
            private set

        private const val COMP = "vpn"

        /**
         * The loaded bundle lists, shared read-only with on-screen detection
         * (text and address-bar hosts) so they aren't held in memory twice.
         * Empty while the filter isn't running.
         */
        @Volatile
        var detectionLists: Map<String, Set<String>> = emptyMap()
            private set

        /** [VpnHealth.State.key] of the running filter; "off" when stopped. */
        @Volatile
        var health = "off"
            private set

        @Volatile
        private var instance: DnsVpnService? = null

        /** From [Monitor]'s screen broadcasts (no receiver of our own). */
        fun onScreen(on: Boolean) {
            val svc = instance ?: return
            svc.healthMonitor.recordScreen(on, System.currentTimeMillis())
            if (on) svc.evaluateHealth(force = true)
        }

        /** From [Monitor]'s battery samples: catches silence with no relay traffic. */
        fun pollHealth() {
            instance?.evaluateHealth(force = true)
        }

        /**
         * Tells the user protection stopped when they didn't ask for it (revoked,
         * failed to start). Shown even with the app closed — a silent gap is the
         * failure mode this whole log exists to prevent.
         */
        fun postStoppedAlert(context: Context, reasonRes: Int) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            ensureAlertChannel(context, nm)
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                ALERT_ID,
                NotificationCompat.Builder(context, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(context.getString(R.string.alert_stopped_title))
                    .setContentText(context.getString(reasonRes))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(reasonRes)))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private fun ensureAlertChannel(context: Context, nm: NotificationManager) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    private val healthMonitor = VpnHealth()
    private val lastHealthEval = AtomicLong(0)
    @Volatile
    private var healthState = VpnHealth.State.OK

    @Volatile
    private var logAllowed = true

    @Volatile
    private var blockStyle = BlockScreen.Style.DEFAULT
    @Volatile private var noticeHighRisk = true
    @Volatile private var partnerRepeated = true

    private var tunFd: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var pool: ExecutorService? = null
    private var tunOut: FileOutputStream? = null
    private val tunWriteLock = Any()

    @Volatile
    private var rules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var adHosts: Set<String> = emptySet()

    // Content-category classification (adult, gambling, …). Lists label a host;
    // the per-category policy decides whether a label blocks. Loaded off-thread.
    @Volatile
    private var categoryLists: Map<String, Set<String>> = emptyMap()

    @Volatile
    private var categoryPolicies: Map<String, Categories.Policy> = emptyMap()

    // Overrides imported-list blocks only (never user rules or DoH guards).
    @Volatile
    private var allowlist: Set<String> = emptySet()

    // Category (bundle) reloads are serialized on one daemon thread and
    // coalesced, so first-run auto-downloads (each bumping the version and
    // re-triggering a reload) can't spawn a herd of concurrent list builds.
    private val categoryReloadExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "focusfence-category-load").apply { isDaemon = true }
    }
    private val categoryReloadPending = AtomicBoolean(false)

    private val usageTracker = UsageTracker()
    private val lastPersistedUsage = HashMap<String, Long>()
    private lateinit var powerManager: PowerManager

    private val statsLock = Any()
    private var statsBufferDay = ""
    private val statsBuffer = HashMap<String, Int>()
    private var statsPending = 0
    private var lastStatsFlush = 0L

    /** The owner's resolver choice (Settings → DNS server); AUTO by default. See [Upstreams]. */
    @Volatile
    private var upstreamConfig: Upstreams.Config = Upstreams.DEFAULT

    /** Hostname-addressed custom servers, resolved on the real network and cached briefly. */
    private val resolvedHosts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, InetAddress>>()

    /** Key of the upstream that most recently answered; tried first so a dead
     *  server isn't re-probed on every query. */
    @Volatile
    private var preferredUpstream: String? = null

    // The real (non-VPN) network and its DNS servers, re-read at most every
    // [UNDERLYING_TTL_MS] so a Wi-Fi <-> mobile switch is picked up quickly
    // without a system call per query.
    @Volatile
    private var underlying: Network? = null

    @Volatile
    private var underlyingDns: List<Upstreams.Entry> = emptyList()

    @Volatile
    private var underlyingCheckedAt = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            BlocklistStore.KEY_RULES -> {
                rules = BlocklistStore.rulesByDomain(this)
                preloadBudgetUsage()
            }
            AdListStore.KEY_ENABLED, AdListStore.KEY_COUNT -> reloadAdHostsAsync()
            CategoryStore.KEY_VERSION -> reloadCategoriesAsync()
            AllowlistStore.KEY_ALLOWLIST -> allowlist = AllowlistStore.get(this)
            LogSettings.KEY_LOG_ALLOWED_DNS -> logAllowed = LogSettings.logAllowedDns(this)
            BlockScreenSettings.KEY_BLOCK_STYLE -> blockStyle = BlockScreenSettings.style(this)
            BlockScreenSettings.KEY_NOTICE_HIGH_RISK -> noticeHighRisk = BlockScreenSettings.noticeHighRisk(this)
            BlockScreenSettings.KEY_PARTNER_REPEATED -> partnerRepeated = BlockScreenSettings.partnerRepeated(this)
            DnsUpstreamStore.KEY_REV -> {
                upstreamConfig = DnsUpstreamStore.load(this)
                preferredUpstream = null
                resolvedHosts.clear()
                EventLog.activity(
                    LogEvents.service(System.currentTimeMillis(), COMP, "upstream", Upstreams.describe(upstreamConfig)),
                    urgent = true,
                )
            }
        }
    }

    /** Seeds the tracker from persisted usage; a cleared store entry (e.g. after
     *  a budget change) resets the in-memory count too. */
    private fun preloadBudgetUsage() {
        val today = LocalDate.now().toString()
        for (rule in rules.values) {
            if (!rule.hasBudget) continue
            val (dayKey, used) = UsageStore.load(this, rule.domain) ?: (today to 0L)
            usageTracker.preload(rule.domain, dayKey, used)
        }
    }

    private fun reloadAdHostsAsync() {
        Thread({
            // Until this finishes, ad-list domains resolve normally — a real (if
            // brief) filtering gap after every start, so its length is measured.
            val started = SystemClock.elapsedRealtime()
            adHosts = if (AdListStore.isEnabled(this)) AdListStore.load(this) else emptySet()
            EventLog.diag(
                LogEvents.service(
                    System.currentTimeMillis(), "adlist", "loaded",
                    "n=${adHosts.size},ms=${SystemClock.elapsedRealtime() - started}",
                )
            )
        }, "focusfence-adlist-load").start()
    }

    private fun reloadCategoriesAsync() {
        // Coalesce: if a reload is already queued, let it pick up the new state.
        if (!categoryReloadPending.compareAndSet(false, true)) return
        categoryReloadExec.execute {
            // Cleared at the start of the run so a change arriving mid-reload
            // queues exactly one more pass instead of being lost or piling up.
            categoryReloadPending.set(false)
            val started = SystemClock.elapsedRealtime()
            categoryPolicies = CategoryStore.policies(this)
            // Fetch any enabled-but-missing bundle (the default-on security ones
            // on first run) so defaults take effect without opening the UI.
            val fetched = CategoryStore.ensureDefaultsDownloaded(this)
            categoryLists = CategoryStore.loadActive(this)
            detectionLists = categoryLists
            EventLog.diag(
                LogEvents.service(
                    System.currentTimeMillis(), "categories", "loaded",
                    "cats=${categoryLists.size},fetched=$fetched,ms=${SystemClock.elapsedRealtime() - started}",
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent (restart) or the system's always-on trigger both mean "start".
        if (intent?.action == ACTION_STOP) {
            // Strict mode: refuse to stop until an unlock window is open.
            if (StrictStore.isLocked(this)) {
                EventLog.activity(
                    LogEvents.service(System.currentTimeMillis(), COMP, "stop_refused", "strict"),
                    urgent = true,
                )
                broadcastState()
                return START_STICKY
            }
            val restart = intent.getBooleanExtra(EXTRA_RESTART, false)
            EventLog.activity(
                LogEvents.service(System.currentTimeMillis(), COMP, "stop", if (restart) "restart" else "user"),
                urgent = true,
            )
            if (!restart) {
                VpnWanted.set(this, false) // the owner turned it off: do not bring it back on boot or update
                app.focusfence.sync.Sync.kick(this)
            }
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another app took the VPN slot, or the user disabled it from settings.
        EventLog.activity(LogEvents.service(System.currentTimeMillis(), COMP, "revoked"), urgent = true)
        postStoppedAlert(this, R.string.alert_stopped_revoked)
        app.focusfence.sync.Sync.kick(this)
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) {
            EventLog.activity(LogEvents.service(System.currentTimeMillis(), COMP, "destroyed"), urgent = true)
        }
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (isRunning) return

        powerManager = getSystemService(PowerManager::class.java)
        logAllowed = LogSettings.logAllowedDns(this)
        blockStyle = BlockScreenSettings.style(this)
        noticeHighRisk = BlockScreenSettings.noticeHighRisk(this)
        partnerRepeated = BlockScreenSettings.partnerRepeated(this)
        rules = BlocklistStore.rulesByDomain(this)
        preloadBudgetUsage()
        reloadAdHostsAsync()
        reloadCategoriesAsync()
        allowlist = AllowlistStore.get(this)
        upstreamConfig = DnsUpstreamStore.load(this)
        StatsStore.prune(this, (0L..13L).map { LocalDate.now().minusDays(it).toString() })
        BlocklistStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            // Critical: establish() returns a NON-blocking fd by default, which
            // would turn the reader loop into a CPU-pinning busy spin.
            .setBlocking(true)
            .setMtu(MTU)
            .addAddress(VPN4_ADDRESS, 24)
            .addDnsServer(VPN4_DNS)
            .addRoute(VPN4_DNS, 32)
        try {
            builder.addAddress(VPN6_ADDRESS, 64)
                .addDnsServer(VPN6_DNS)
                .addRoute(VPN6_DNS, 128)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IPv6 tunnel config rejected, continuing with IPv4 only", e)
            EventLog.diag(LogEvents.error(System.currentTimeMillis(), COMP, "ipv6_rejected", e.message))
        }
        // VPNs default to METERED on Android 10+, and the system treats the
        // device as on a metered link while any VPN is up. That makes
        // downloaders, the Play Store and Data Saver throttle or defer
        // transfers even though none of that traffic touches this tunnel.
        // false = inherit meteredness from the real (underlying) network.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        for (pkg in ExcludedAppsStore.get(this)) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                // App was uninstalled; skip.
            }
        }

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null (no user consent?)")
            EventLog.activity(
                LogEvents.service(System.currentTimeMillis(), COMP, "establish_failed"),
                urgent = true,
            )
            postStoppedAlert(this, R.string.alert_stopped_establish)
            app.focusfence.sync.Sync.kick(this)
            stopSelf()
            return
        }

        tunFd = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        // Relays block on network I/O (up to a few seconds when an upstream is
        // dead), so a small pool backs the queue up and every lookup stalls.
        pool = Executors.newFixedThreadPool(12)
        isRunning = true
        VpnWanted.set(this, true)
        blockedCount.set(0)
        alwaysOnState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                isLockdownEnabled -> "lockdown"
                isAlwaysOn -> "on"
                else -> "off"
            }
        } else {
            "unknown"
        }
        maybeNudgeAlwaysOn()
        EventLog.activity(
            LogEvents.service(
                System.currentTimeMillis(), COMP, "start",
                "rules=${rules.size},excluded=${ExcludedAppsStore.get(this).size}," +
                    "adlist=${AdListStore.isEnabled(this)},always_on=$alwaysOnState",
            ),
            urgent = true,
        )
        Monitor.attach(this, COMP)

        healthMonitor.reset(System.currentTimeMillis(), powerManager.isInteractive)
        healthState = VpnHealth.State.OK
        health = healthState.key
        instance = this
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)

        startForeground(NOTIFICATION_ID, buildNotification())
        refreshUnderlying(force = true)

        readerThread = Thread({ readLoop(fd) }, "focusfence-tun-reader").also {
            it.isDaemon = true
            it.start()
        }
        broadcastState()
        // Flag a broken setup (lockdown, strict Private DNS) right away.
        evaluateHealth(force = true)
    }

    private fun stopVpn() {
        if (!isRunning && tunFd == null) return
        isRunning = false
        alwaysOnState = "unknown"
        instance = null
        health = "off"
        detectionLists = emptyMap()
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)
        Monitor.detach(COMP)
        BlocklistStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        for ((domain, state) in usageTracker.snapshot()) {
            UsageStore.save(this, domain, state.first, state.second)
        }
        synchronized(statsLock) { flushStatsLocked() }
        pool?.shutdownNow()
        pool = null
        // Closing the fd unblocks the reader thread's read().
        try {
            tunFd?.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing tun fd", e)
        }
        tunFd = null
        tunOut = null
        readerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        EventLog.flushAsync()
        broadcastState()
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        while (isRunning) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                // Expected when stopVpn() closes the fd; anything else is a
                // silent end of filtering and must be visible.
                if (isRunning) {
                    EventLog.diag(LogEvents.error(System.currentTimeMillis(), COMP, "tun_read", e.toString()))
                }
                break
            }
            if (n < 0) break
            if (n == 0) continue
            // CPU attribution: parse + decide + local answers, on the reader thread.
            val cpu0 = android.os.Debug.threadCpuTimeNanos()
            handlePacket(buf.copyOf(n))
            Monitor.perf.count("dns_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    private fun handlePacket(packet: ByteArray) {
        val udp = Packets.parseUdp(packet, packet.size)
        if (udp == null) {
            Monitor.perf.count("pkt_unparsed")
            return
        }
        if (udp.dstPort != 53) {
            Monitor.perf.count("pkt_not_dns")
            return
        }
        Monitor.perf.count("q")
        healthMonitor.recordQuery(System.currentTimeMillis())

        val question = Dns.parseQuestion(udp.packet, udp.payloadOffset, udp.payloadLength)
        if (question == null) Monitor.perf.count("q_unparsed")
        if (question != null) {
            val decision = decide(question.qname)
            if (decision.blocks) {
                Monitor.perf.count("blk_" + decision.reason)
                EventLog.activity(
                    LogEvents.dns(
                        System.currentTimeMillis(), question.qname, question.qtype,
                        LogEvents.DECISION_BLOCKED, decision.reason, decision.rule, decision.categories,
                    )
                )
            } else if (logAllowed) {
                EventLog.activity(
                    LogEvents.dns(
                        System.currentTimeMillis(), question.qname, question.qtype,
                        LogEvents.DECISION_ALLOWED, decision.reason, decision.rule, decision.categories,
                    )
                )
            }
            val reason = statsBucket(decision)
            if (reason != null) {
                blockedCount.incrementAndGet()
                recordStat(reason)
                val ttl = if (reason == StatsCodec.KEY_ADS || reason == StatsCodec.KEY_GUARD) {
                    AD_BLOCK_TTL_SECONDS
                } else {
                    RULE_BLOCK_TTL_SECONDS
                }
                val dnsResponse =
                    Dns.buildBlockedResponse(udp.packet, udp.payloadOffset, question, ttl)
                writeToTun(Packets.buildUdpReply(udp, dnsResponse))
                // After the answer is written, so a notice never delays it. Triage (D-024): ads, trackers and
                // guards stay silent; a user rule, or an adult/gambling/scam site's front door, is a notice.
                val rule = decision.rule
                val screenOn = powerManager.isInteractive
                if (decision.reason == LogEvents.REASON_RULE && rule != null) {
                    if (blockStyle != BlockScreen.Style.OFF && BlockScreen.shouldSignal(question.qname, rule, screenOn)) {
                        app.focusfence.BlockedNotice.onDnsBlock(this, rule.domain, null, tellMe = true, tellPartner = false)
                    }
                } else if (decision.reason == LogEvents.REASON_CATEGORY && screenOn) {
                    val category = BlockTriage.highRisk(decision.categories)
                    val site = category?.let { BlockTriage.frontDoorSite(question.qname) }
                    val tellMe = noticeHighRisk && blockStyle != BlockScreen.Style.OFF
                    if (category != null && site != null && (tellMe || partnerRepeated)) {
                        app.focusfence.BlockedNotice.onDnsBlock(this, site, category, tellMe, partnerRepeated)
                    }
                }
                return
            }
        }

        // Not blocked (or unparseable): relay to a real resolver off the reader thread.
        val query = udp.copyPayload()
        pool?.execute { forward(udp, query) }
    }

    /**
     * The full precedence lives in the pure [Categories.decide]: a blocking user
     * rule, then DoH guards, then the allowlist (overriding imported lists),
     * then a BLOCK-policy category, then the ad list. The covering rule and all
     * matched category labels are kept even when nothing blocks, so the log can
     * record what a host *is*, not only what was stopped.
     */
    private fun decide(qname: String): Categories.Decision = Categories.decide(
        qname = qname,
        rule = Blocklist.findRule(qname, rules),
        ruleBlocks = { ruleBlocks(it, qname) },
        allowlist = allowlist,
        categoryLists = categoryLists,
        policies = categoryPolicies,
        adHosts = adHosts,
        guards = Blocklist.DOH_BYPASS_GUARDS,
    )

    /** Null if allowed; otherwise the stats bucket: ads, DoH guard, category id, or the rule's domain. */
    private fun statsBucket(d: Categories.Decision): String? {
        if (!d.blocks) return null
        return when (d.reason) {
            LogEvents.REASON_ADS -> StatsCodec.KEY_ADS
            LogEvents.REASON_GUARD -> StatsCodec.KEY_GUARD
            LogEvents.REASON_CATEGORY -> d.categories.firstOrNull { categoryPolicies[it] == Categories.Policy.BLOCK }
                ?.let { "cat:$it" } ?: StatsCodec.KEY_ADS
            else -> d.rule?.domain
        }
    }

    private fun recordStat(bucket: String) {
        val day = LocalDate.now().toString()
        synchronized(statsLock) {
            if (day != statsBufferDay) flushStatsLocked()
            statsBufferDay = day
            statsBuffer.merge(bucket, 1, Int::plus)
            statsPending++
            val now = System.currentTimeMillis()
            if (statsPending >= 25 || now - lastStatsFlush >= 30_000L) flushStatsLocked()
        }
    }

    private fun flushStatsLocked() {
        if (statsBuffer.isNotEmpty() && statsBufferDay.isNotEmpty()) {
            StatsStore.add(this, statsBufferDay, statsBuffer)
        }
        statsBuffer.clear()
        statsPending = 0
        lastStatsFlush = System.currentTimeMillis()
    }

    /** Observes the world and accrues usage; the decision itself lives in [RuleEvaluator]. */
    private fun ruleBlocks(rule: BlockRule, qname: String): Boolean {
        val localTime = LocalTime.now()
        val now = RuleEvaluator.Now(
            minuteOfDay = localTime.hour * 60 + localTime.minute,
            epochMillis = System.currentTimeMillis(),
            dayKey = LocalDate.now().toString(),
            screenOn = powerManager.isInteractive,
        )
        var used = 0L
        if (RuleEvaluator.requiresUsage(rule, qname)) {
            used = usageTracker.recordAndGetUsage(
                rule.domain, now.epochMillis, now.dayKey, accrue = now.screenOn,
            )
            persistUsageThrottled(rule.domain, now.dayKey, used)
        }
        return RuleEvaluator.blocks(rule, qname, now, used)
    }

    /** Writes usage through to prefs at most every 15s of accrued time per domain. */
    private fun persistUsageThrottled(domain: String, dayKey: String, usedMillis: Long) {
        val last = lastPersistedUsage[domain]
        if (last == null || usedMillis - last >= 15_000L || usedMillis < last) {
            lastPersistedUsage[domain] = usedMillis
            UsageStore.save(this, domain, dayKey, usedMillis)
        }
    }

    /** CPU attribution for the relay (sockets, TLS for DoH), on pool threads. */
    private fun forward(udp: Packets.UdpDatagram, query: ByteArray) {
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        try {
            forwardInner(udp, query)
        } finally {
            Monitor.perf.count("fwd_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    /** One attempt at one resolver; any failure is a null so the caller moves on. */
    private fun tryUpstream(upstream: Upstreams.Entry, query: ByteArray): ByteArray? = try {
        when (upstream) {
            is Upstreams.Entry.Udp -> resolve(upstream.host)?.let { udpQuery(it, upstream.port, query) }
            is Upstreams.Entry.Doh -> dohQuery(upstream.url, query)
        }.also { if (it == null) countFailure(upstream, null) }
    } catch (e: Exception) {
        countFailure(upstream, e)
        null
    }

    /** Which kind of resolver failed and how ([Faults.netKind]; "bad" = it answered, unusably, or had no address). */
    private fun countFailure(upstream: Upstreams.Entry, e: Exception?) {
        val via = if (upstream is Upstreams.Entry.Udp) "udp" else "doh"
        Monitor.perf.count("fwd_err_${via}_${e?.let(app.focusfence.dns.Faults::netKind) ?: "bad"}")
    }

    private fun forwardInner(udp: Packets.UdpDatagram, query: ByteArray) {
        refreshUnderlying(force = false)
        val first = preferredUpstream
        val order = Upstreams.order(upstreamConfig, underlyingDns, first)
        val started = SystemClock.elapsedRealtime()
        // Automatic mode only: race the resolvers instead of waiting out each dead one. A custom server never leaks a
        // query to a public resolver early; it keeps the strict order below.
        if (upstreamConfig.mode == Upstreams.Mode.AUTO && order.size > 1) {
            val attempts = order.map { upstream -> { tryUpstream(upstream, query) } }
            val win = app.focusfence.dns.Hedged.race(attempts, HEDGE_MS, hedgePool,
                onFail = { Monitor.perf.count("fwd_attempt_fail") }, onHedge = { Monitor.perf.count("fwd_hedged") })
            if (win != null) {
                val upstream = order[win.index]
                preferredUpstream = upstream.key
                writeToTun(Packets.buildUdpReply(udp, win.value, win.value.size))
                Monitor.perf.latency(SystemClock.elapsedRealtime() - started)
                Monitor.perf.count("fwd")
                Monitor.perf.count(if (upstream is Upstreams.Entry.Udp) "fwd_udp" else "fwd_doh")
                if (upstream.key != first) Monitor.perf.count("fwd_failover")
                healthMonitor.recordForward(true)
                evaluateHealth(force = false)
                return
            }
            refreshUnderlying(force = true)
            Monitor.perf.count("fwd_fail")
            healthMonitor.recordForward(false)
            evaluateHealth(force = false)
            return
        }
        for ((i, upstream) in order.withIndex()) {
            // Any failure (I/O, TLS, a vanished network) is a null, so the next one is tried:
            // an escaping exception would silently drop the query.
            val answer = tryUpstream(upstream, query)
            if (answer != null) {
                preferredUpstream = upstream.key
                writeToTun(Packets.buildUdpReply(udp, answer, answer.size))
                Monitor.perf.latency(SystemClock.elapsedRealtime() - started)
                Monitor.perf.count("fwd")
                Monitor.perf.count(if (upstream is Upstreams.Entry.Udp) "fwd_udp" else "fwd_doh")
                if (upstream.key != first) Monitor.perf.count("fwd_failover")
                // The owner's chosen server didn't answer and a fallback did: worth
                // knowing in diag ("is my own DNS server actually up?").
                if (upstreamConfig.mode != Upstreams.Mode.AUTO && i > 0 && upstream.key !in chosenKeys()) {
                    Monitor.perf.count("fwd_chosen_failed")
                }
                healthMonitor.recordForward(true)
                evaluateHealth(force = false)
                return
            }
            Monitor.perf.count("fwd_attempt_fail")
        }
        // The network may have changed under us; re-read it for the next query.
        refreshUnderlying(force = true)
        // Every resolver failed: the client gets no answer. Often just "offline",
        // but a high rate while online means the relay itself is broken.
        Monitor.perf.count("fwd_fail")
        healthMonitor.recordForward(false)
        evaluateHealth(force = false)
    }

    /**
     * Re-derives filter health and acts on a change: an urgent `svc vpn health`
     * record, the ongoing notification's text, and a separate alert while there's
     * a real problem. Throttled on the relay path; forced from broadcasts.
     */
    private fun evaluateHealth(force: Boolean) {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        val last = lastHealthEval.get()
        if (!force && now - last < HEALTH_EVAL_INTERVAL_MS) return
        if (!lastHealthEval.compareAndSet(last, now)) return
        val next = healthMonitor.evaluate(now, lockdownOn(), privateDnsStrict(), ::hasUnderlyingNetwork)
        val prev = healthState
        if (next == prev) return
        healthState = next
        health = next.key
        val rate = healthMonitor.failureRate()
        EventLog.activity(
            LogEvents.service(
                now, COMP, "health",
                next.key + (if (rate != null) ",fail_pct=${(rate * 100).toInt()}" else "") + ",was=${prev.key}",
            ),
            urgent = true,
        )
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (isRunning) nm.notify(NOTIFICATION_ID, buildNotification())
        if (next.problem) {
            ensureAlertChannel(this, nm)
            // Lockdown can only be turned off in system VPN settings; go there.
            val target = if (next == VpnHealth.State.LOCKDOWN) {
                Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(this, MainActivity::class.java)
            }
            val open = PendingIntent.getActivity(this, 1, target, PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                ALERT_ID,
                NotificationCompat.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(getString(R.string.alert_health_title))
                    .setContentText(getString(healthText(next)))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(getString(healthText(next))))
                    .setContentIntent(open)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        } else {
            nm.cancel(ALERT_ID)
        }
        broadcastState()
    }

    /**
     * Android drops Always-on when the VPN is toggled from the system switch. Remind the owner, at most every
     * 12 h ([AlwaysOnNudge]); the notification goes away once it's on again. Its own prefs file, so the
     * timestamp doesn't trip the rules listeners that watch `focusfence`.
     */
    private fun maybeNudgeAlwaysOn() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val prefs = getSharedPreferences("focusfence_nudge", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (alwaysOnState != "off") {
            nm.cancel(NUDGE_ID)
            return
        }
        if (!AlwaysOnNudge.shouldNudge(alwaysOnState, app.focusfence.admin.DeviceAdmin.isDeviceOwner(this), prefs.getLong("always_on", 0), now)) return
        prefs.edit().putLong("always_on", now).apply()
        ensureAlertChannel(this, nm)
        val open = PendingIntent.getActivity(
            this, 2, Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NUDGE_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.alert_always_on_title))
                .setContentText(getString(R.string.alert_always_on_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.alert_always_on_text)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        Monitor.perf.count("always_on_nudge")
    }

    private fun healthText(state: VpnHealth.State): Int = when (state) {
        VpnHealth.State.OK -> R.string.notification_text
        VpnHealth.State.OFFLINE -> R.string.health_offline
        VpnHealth.State.DEGRADED -> R.string.health_degraded
        VpnHealth.State.UPSTREAM_DOWN -> R.string.health_upstream_down
        VpnHealth.State.SILENT -> R.string.health_silent
        VpnHealth.State.BYPASSED -> R.string.health_bypassed
        VpnHealth.State.LOCKDOWN -> R.string.health_lockdown
    }

    /**
     * "Block connections without VPN". Our tunnel routes only DNS, so lockdown
     * drops every other packet: the phone has DNS but no internet.
     */
    private fun lockdownOn(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled

    /** Strict ("hostname") Private DNS bypasses the tunnel. Not public API; unreadable = false. */
    private fun privateDnsStrict(): Boolean = try {
        AndroidSettings.Global.getString(contentResolver, "private_dns_mode") == "hostname"
    } catch (e: SecurityException) {
        false
    }

    /** Is any real (non-VPN) network with internet up? Only asked when relays fail. */
    @Suppress("DEPRECATION")
    private fun hasUnderlyingNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            // VALIDATED, not just INTERNET: a captive portal (plane/hotel Wi-Fi) has
            // "internet" but can't resolve anything, and that's the network's
            // fault, not a filter problem (seen in the 2026-11-19 export).
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** Plain UDP to the network's own resolver, on a socket bound to that network. */
    private fun chosenKeys(): Set<String> {
        val c = upstreamConfig
        return when (c.mode) {
            Upstreams.Mode.AUTO -> emptySet()
            Upstreams.Mode.PRESET -> Upstreams.PRESETS.firstOrNull { it.id == c.presetId }?.entries.orEmpty().map { it.key }.toSet()
            Upstreams.Mode.CUSTOM -> c.custom.map { it.key }.toSet()
        }
    }

    /** IP literals parse without a lookup; hostnames resolve on the real network (never via our tunnel). */
    private fun resolve(host: String): InetAddress? {
        val literal = host.contains(':') || host.all { it.isDigit() || it == '.' }
        if (literal) return runCatching { InetAddress.getByName(host) }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        resolvedHosts[host]?.let { (at, addr) -> if (now - at < 10 * 60_000L) return addr }
        val addr = runCatching { (underlying?.getAllByName(host) ?: InetAddress.getAllByName(host)).firstOrNull() }.getOrNull()
            ?: return null
        resolvedHosts[host] = now to addr
        return addr
    }

    private fun udpQuery(server: InetAddress, port: Int, query: ByteArray): ByteArray? {
        DatagramSocket().use { socket ->
            // Bound to the real network so it can't loop back into the tunnel.
            val net = underlying
            if (net != null) net.bindSocket(socket) else if (!protect(socket)) return null
            socket.soTimeout = UDP_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, port)))
            val buf = ByteArray(MTU - 48)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            // Mismatched transaction id = stray packet; treat as a failure.
            if (resp.length < 2 || buf[0] != query[0] || buf[1] != query[1]) return null
            return buf.copyOf(resp.length)
        }
    }

    /**
     * RFC 8484 DoH: POST the wire-format query as application/dns-message. Opened
     * on the real network explicitly, and the connection is NOT disconnect()ed —
     * on Android that closes the socket, forcing a fresh TCP+TLS handshake for
     * every lookup. Draining and closing the streams lets keep-alive reuse it.
     */
    private fun dohQuery(endpoint: String, query: ByteArray): ByteArray? {
        val url = URL(endpoint)
        val net = underlying
        val conn = ((net?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection).apply {
            connectTimeout = UPSTREAM_TIMEOUT_MS
            readTimeout = UPSTREAM_TIMEOUT_MS
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/dns-message")
            setRequestProperty("Accept", "application/dns-message")
            setFixedLengthStreamingMode(query.size)
        }
        conn.outputStream.use { it.write(query) }
        if (conn.responseCode != 200) {
            runCatching { conn.errorStream?.use { it.readBytes() } }
            return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        // Must fit a synthesized UDP reply; DNS answers are far smaller.
        return if (bytes.isEmpty() || bytes.size > MTU - 48) null else bytes
    }

    /** Re-reads the real network and its resolvers (cheap, but not per query). */
    @Suppress("DEPRECATION")
    private fun refreshUnderlying(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - underlyingCheckedAt < UNDERLYING_TTL_MS) return
        underlyingCheckedAt = now
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val candidates = cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            n to caps
        }
        // Prefer a validated, unmetered (Wi-Fi) network, like the system does.
        val best = candidates.sortedWith(
            compareByDescending<Pair<Network, NetworkCapabilities>> {
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenByDescending { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        ).firstOrNull()?.first
        underlying = best
        underlyingDns = best?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
            .filter { it.hostAddress != VPN4_DNS && !it.hostAddress.orEmpty().startsWith("fd66:f0cf") }
            .take(2)
            .mapNotNull { a -> a.hostAddress?.let { Upstreams.Entry.Udp(it, 53) } }
    }

    private fun writeToTun(packet: ByteArray) {
        val out = tunOut ?: return
        try {
            synchronized(tunWriteLock) { out.write(packet) }
        } catch (e: IOException) {
            Log.w(TAG, "writing to tun", e)
            Monitor.perf.count("tun_write_fail")
        }
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(healthText(healthState)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
