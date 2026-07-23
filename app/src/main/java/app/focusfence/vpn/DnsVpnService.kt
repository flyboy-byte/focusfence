package app.focusfence.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.focusfence.MainActivity
import app.focusfence.R
import app.focusfence.data.AdListStore
import app.focusfence.data.BlocklistStore
import app.focusfence.dns.BlockRule
import app.focusfence.dns.Blocklist
import app.focusfence.dns.Dns
import app.focusfence.dns.Packets
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A DNS-only filtering VPN.
 *
 * The tunnel is configured so that ONLY packets addressed to the tunnel's virtual
 * DNS servers are routed into it — all other traffic takes the normal network path,
 * which is why running this costs essentially no battery or throughput. Queries for
 * blocked domains are answered NXDOMAIN locally; everything else is relayed to a
 * real resolver over a protected socket.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "FocusFence"

        const val ACTION_START = "app.focusfence.action.START"
        const val ACTION_STOP = "app.focusfence.action.STOP"
        const val ACTION_STATE_CHANGED = "app.focusfence.action.STATE_CHANGED"

        private const val VPN4_ADDRESS = "10.111.222.1"
        private const val VPN4_DNS = "10.111.222.3"
        private const val VPN6_ADDRESS = "fd66:f0cf:0:1::1"
        private const val VPN6_DNS = "fd66:f0cf:0:1::53"

        private const val MTU = 8192
        private const val NOTIFICATION_CHANNEL = "vpn_status"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        val blockedCount = AtomicLong(0)
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var pool: ExecutorService? = null
    private var tunOut: FileOutputStream? = null
    private val tunWriteLock = Any()

    @Volatile
    private var rules: Map<String, BlockRule> = emptyMap()

    @Volatile
    private var adHosts: Set<String> = emptySet()

    private val upstreams: List<InetAddress> = listOf(
        InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
        InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
    )

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            BlocklistStore.KEY_RULES -> rules = BlocklistStore.rulesByDomain(this)
            AdListStore.KEY_ENABLED, AdListStore.KEY_COUNT -> reloadAdHostsAsync()
        }
    }

    private fun reloadAdHostsAsync() {
        Thread({
            adHosts = if (AdListStore.isEnabled(this)) AdListStore.load(this) else emptySet()
        }, "focusfence-adlist-load").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent (restart) or the system's always-on trigger both mean "start".
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another app took the VPN slot, or the user disabled it from settings.
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (isRunning) return

        rules = BlocklistStore.rulesByDomain(this)
        reloadAdHostsAsync()
        BlocklistStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
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
        }

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null (no user consent?)")
            stopSelf()
            return
        }

        tunFd = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        pool = Executors.newFixedThreadPool(4)
        isRunning = true
        blockedCount.set(0)

        startForeground(NOTIFICATION_ID, buildNotification())

        readerThread = Thread({ readLoop(fd) }, "focusfence-tun-reader").also {
            it.isDaemon = true
            it.start()
        }
        broadcastState()
    }

    private fun stopVpn() {
        if (!isRunning && tunFd == null) return
        isRunning = false
        BlocklistStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
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
        broadcastState()
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        while (isRunning) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                break // fd closed by stopVpn()
            }
            if (n <= 0) continue
            handlePacket(buf.copyOf(n))
        }
    }

    private fun handlePacket(packet: ByteArray) {
        val udp = Packets.parseUdp(packet, packet.size) ?: return
        if (udp.dstPort != 53) return

        val question = Dns.parseQuestion(udp.packet, udp.payloadOffset, udp.payloadLength)
        if (question != null && isBlocked(question.qname)) {
            blockedCount.incrementAndGet()
            val dnsResponse = Dns.buildNxDomain(udp.packet, udp.payloadOffset, question.questionEnd)
            writeToTun(Packets.buildUdpReply(udp, dnsResponse))
            return
        }

        // Not blocked (or unparseable): relay to a real resolver off the reader thread.
        val query = udp.copyPayload()
        pool?.execute { forward(udp, query) }
    }

    private fun isBlocked(qname: String): Boolean {
        val rule = Blocklist.findRule(qname, rules)
        if (rule != null) {
            val now = LocalTime.now()
            if (rule.activeAt(now.hour * 60 + now.minute)) return true
        }
        if (adHosts.isNotEmpty() && qname in adHosts) return true
        return Blocklist.matches(qname, Blocklist.DOH_BYPASS_GUARDS)
    }

    private fun forward(udp: Packets.UdpDatagram, query: ByteArray) {
        for (server in upstreams) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) return
                    socket.soTimeout = 4000
                    socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                    val respBuf = ByteArray(MTU - 48)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(resp)
                    writeToTun(Packets.buildUdpReply(udp, respBuf, resp.length))
                }
                return
            } catch (e: IOException) {
                // Timeout or network error: try the next upstream.
            }
        }
    }

    private fun writeToTun(packet: ByteArray) {
        val out = tunOut ?: return
        try {
            synchronized(tunWriteLock) { out.write(packet) }
        } catch (e: IOException) {
            Log.w(TAG, "writing to tun", e)
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
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
