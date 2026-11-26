package app.focusfence.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Flat-file store, on purpose. Activity records are appended as JSON Lines in
 * the *same format the phone writes and exports* (docs/specs/event-log.md), one
 * file per device, so there is one schema end to end and an operator can read
 * the data with the same tools/analyze_logs.py. Device metadata (partners,
 * invites) is a small JSON file per device. Reports and sealed keys are opaque ciphertext.
 * Prototype: swap in Postgres + object storage before anything but personal or
 * small-group use.
 */
class Store(private val root: File) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Any>()
    val dir: File get() = root

    init {
        File(root, "devices").mkdirs()
    }

    /**
     * Plan 13: one kind of partner, a person with one or more devices (their app, or a browser). Each device made its own
     * key pair; the owner's phone seals the report key to each device's public key ([PartnerDevice.sealed]), so the
     * server relays only public keys and ciphertext (D-023). Email or app only decides how alerts reach them.
     */
    @Serializable
    data class Partner(
        val id: String,
        var name: String,
        /** Secret in the partner's report link (/p/{token}), the same for all their devices. Alert emails carry it. */
        val token: String,
        val linkedMs: Long,
        /** Set only when they joined through the emailed link, so the address is proven theirs. Empty: alerts go to their app. */
        var email: String = "",
        var devices: List<PartnerDevice> = emptyList(),
        val inviteId: String = "",
        /** Plan 12 §2: when they last opened the report (its data, fetched by the page's script). The owner sees it. */
        var lastViewMs: Long = 0,
        /** Plan 12 §5: their choice of how alerts reach them ([app.focusfence.dns.AlertPrefs]), set on their page. */
        var alertLevel: String = "all",
        var quiet: Boolean = false,
        var zone: String = "UTC",
        var quietFrom: Int = app.focusfence.dns.AlertPrefs.QUIET_FROM_HOUR,
        var quietTo: Int = app.focusfence.dns.AlertPrefs.QUIET_TO_HOUR,
        /** Alerts waiting for quiet hours to end or for the daily message, oldest first (email only; the app holds its own). */
        var held: List<AlertEntry> = emptyList(),
        var lastFlushDay: Long = 0,
    ) {
        fun prefs() = app.focusfence.dns.AlertPrefs.Prefs(app.focusfence.dns.AlertPrefs.Level.fromKey(alertLevel), quiet, zone, quietFrom, quietTo)

        /** At least one of their devices has the report key: they can read it, and their answers to asks count. */
        val ready: Boolean get() = devices.any { it.sealed != null }

        /**
         * Email alerts go out only when no app of theirs reads the alert feed: one path, never two (plan 12 §8). An app
         * that stopped reading for [APP_QUIET_MS] (uninstalled, signed out) hands alerts back to email.
         */
        fun emailsAlerts(now: Long): Boolean = email.isNotEmpty() && devices.none { it.kind == KIND_APP && now - it.lastFeedMs < APP_QUIET_MS }
    }

    @Serializable
    data class PartnerDevice(
        val id: String,
        /** [KIND_APP] or [KIND_WEB]. */
        val kind: String,
        /** What the partner called it, or "Pixel 8" / "Firefox on Linux" from the device; shown to the owner. */
        val label: String,
        /** The device's own P-256 public key (SPKI, base64). The report key is sealed to it with [app.focusfence.dns.Ecies]. */
        val publicKeySpki: String,
        val addedMs: Long,
        /** The report key sealed to this device by the owner's phone, and which key it is ([Device.reportKeyId]). */
        var sealed: String? = null,
        var keyId: String = "",
        var sealedMs: Long = 0,
        /** An app device's last read of the alert feed. */
        var lastFeedMs: Long = 0,
    )

    /**
     * Plan 13: an invite, single use. [codeHash] is the short code (typed, or in the QR code's link); [linkHash] the long
     * secret in an emailed link, which proves the address when used.
     */
    @Serializable
    data class Invite(
        val id: String, val codeHash: String, val expiresMs: Long,
        val linkHash: String = "", val email: String = "", val createdMs: Long = 0,
    )

    @Serializable
    data class Device(
        val id: String,
        val publicKeySpki: String,
        var expectedNextSeq: Long = 1,
        var lastHash: String = "GENESIS",
        var lastSeenMs: Long = 0,
        var enrolledMs: Long = 0,
        var lastReportMs: Long = 0,
        var staleAlerted: Boolean = false,
        var missingRecords: Long = 0,
        var partners: List<Partner> = emptyList(),
        var invites: List<Invite> = emptyList(),
        /** Plan 13: 1 for devices from before the partner rebuild; [migrateToV2] clears their partner data. */
        var schema: Int = 1,
        /** What the owner calls themselves in invites ("Logan wants you as a partner"). Set by the phone. */
        var ownerName: String = "",
        /** Short id of the report key the phone uses now (sent with each heartbeat). Devices sealed to another key are re-sealed. */
        var reportKeyId: String = "",
        /** A partner left or removed a device: the phone should start a new report key, so they can't read on. */
        var rotateKey: Boolean = false,
        /** Which enrollment code let this phone in (EnrollCodes id; empty for the bootstrap token). Admin page only. */
        var codeId: String = "",
        /** A name the operator gave the device on the admin page. */
        var label: String = "",
        /**
         * What partners were told, newest last (2026-11-25): app partners read it from /p/{token}/alerts, so they hear
         * the same things as email partners, as fast. Alert lines only, the text emails already carry (D-023).
         */
        var alerts: List<AlertEntry> = emptyList(),
        /**
         * Things the owner's own phone should hear about (plan 12): a partner who left, so nobody believes they're
         * still watched. Read back in the heartbeat response; kept like [alerts].
         */
        var ownerNotices: List<AlertEntry> = emptyList(),
        /** Plan 12 §3: the owner's latest "ask a partner" request, open or answered. One at a time. */
        var unlockRequest: UnlockRequest? = null,
    )

    /** [state] as stored; [app.focusfence.dns.UnlockAsk.state] adds "lapsed". [by] is the answering partner's name. */
    @Serializable
    data class UnlockRequest(
        val id: String, val action: String, val askedMs: Long,
        val state: String = app.focusfence.dns.UnlockAsk.PENDING, val by: String = "", val decidedMs: Long = 0,
    )

    /** [kind] tells the owner's phone what a notice is about ("left", "device", "joined", "reply"); free text otherwise. */
    @Serializable
    data class AlertEntry(val t: Long, val text: String, val important: Boolean = true, val kind: String = "")

    private fun deviceFile(id: String) = File(root, "devices/$id.json")
    private fun recordsFile(id: String) = File(root, "devices/$id.activity.jsonl")
    fun lock(id: String): Any = locks.computeIfAbsent(id) { Any() }

    fun createDevice(publicKeySpki: String, now: Long): Device {
        val id = java.util.UUID.randomUUID().toString()
        val d = Device(id, publicKeySpki, enrolledMs = now, lastSeenMs = now, lastReportMs = now, schema = 2)
        saveDevice(d)
        return d
    }

    fun device(id: String): Device? {
        if (!SAFE_ID.matches(id)) return null
        return deviceFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Device.serializer(), it.readText()) }
    }

    fun saveDevice(d: Device) = synchronized(lock(d.id)) {
        writeAtomic(deviceFile(d.id), json.encodeToString(Device.serializer(), d))
    }

    /** Write to a temp file and rename, so a crash or full disk can't leave half a JSON file. */
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    // ---- quotas: the data dir shares a disk with other services, so growth is bounded ----

    @Volatile private var usedCache = 0L
    @Volatile private var usedAt = 0L

    /** Bytes under the data dir, recomputed at most every 30 s (a handful of files per device). */
    fun usedBytes(nowMs: Long = System.currentTimeMillis()): Long {
        if (nowMs - usedAt > 30_000 || nowMs < usedAt) {
            usedCache = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            usedAt = nowMs
        }
        return usedCache
    }

    fun deviceCount(): Int = File(root, "devices").listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /** Read-modify-write under the device lock. */
    fun <T> updateDevice(id: String, block: (Device) -> T): T? = synchronized(lock(id)) {
        val d = device(id) ?: return null
        val r = block(d)
        saveDevice(d)
        r
    }

    /** Bytes a device occupies (reports and anything older versions left), for the admin page. */
    fun deviceBytes(id: String): Long {
        if (!SAFE_ID.matches(id)) return 0
        return File(root, "devices").listFiles { f -> f.name.startsWith("$id.") }.orEmpty().sumOf { it.length() }
    }

    /** Removes a device and everything stored for it. Only the admin page calls this. */
    fun deleteDevice(id: String): Boolean = synchronized(lock(id)) {
        if (!SAFE_ID.matches(id) || !deviceFile(id).exists()) return false
        deviceFile(id).delete(); recordsFile(id).delete()
        File(root, "devices").listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        true
    }

    fun allDevices(): List<Device> =
        File(root, "devices").listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { json.decodeFromString(Device.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()

    /** The device and partner a report-link token belongs to (constant-time compare). */
    fun partnerByToken(token: String): Pair<Device, Partner>? {
        if (token.length < 20) return null
        for (d in allDevices()) d.partners.firstOrNull { java.security.MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }?.let { return d to it }
        return null
    }

    /** The live invite whose short code or emailed-link secret hashes to [hash], and the phone that made it. */
    fun inviteByHash(hash: String, now: Long): Pair<Device, Invite>? {
        if (hash.isEmpty()) return null
        fun same(x: String) = x.isNotEmpty() && java.security.MessageDigest.isEqual(x.toByteArray(), hash.toByteArray())
        for (d in allDevices()) d.invites.firstOrNull { it.expiresMs > now && (same(it.codeHash) || same(it.linkHash)) }?.let { return d to it }
        return null
    }

    /**
     * Plan 13 clean cut: partner data from before the rebuild (email partners, app partners, pairing mailboxes, groups)
     * is deleted, not converted, so no old path lingers. Runs at every start; returns how many devices it cleared.
     */
    fun migrateToV2(): Int {
        // Pairing mailboxes and the pairing partners' encrypted screenshots: nothing reads either any more.
        for (old in listOf("mailbox", "blobs")) File(root, old).takeIf { it.isDirectory }?.deleteRecursively()
        var n = 0
        for (d in allDevices()) if (d.schema < 2) {
            updateDevice(d.id) { it.partners = emptyList(); it.invites = emptyList(); it.alerts = emptyList(); it.ownerNotices = emptyList(); it.unlockRequest = null; it.schema = 2 }
            n++
        }
        return n
    }

    /**
     * D-023: activity records are never written. An older app still uploading batches only advances the cursor here;
     * its records are checked for alerts in memory and dropped.
     */
    fun advanceCursor(id: String, lastSeq: Long, newHash: String, gap: Long, now: Long) =
        synchronized(lock(id)) {
            val d = device(id) ?: return@synchronized
            d.expectedNextSeq = lastSeq + 1
            d.lastHash = newHash
            d.missingRecords += gap
            saveDevice(d)
        }

    /**
     * The phone checked in. Returns when it went quiet if partners had been told it was silent, so the caller can tell
     * them it is back; null otherwise.
     */
    fun markSeen(id: String, now: Long): Long? = updateDevice(id) { d ->
        val silentSince = d.lastSeenMs.takeIf { d.staleAlerted }
        d.lastSeenMs = now
        d.staleAlerted = false
        silentSince
    }

    /** Keeps the newest [ALERTS_KEEP] within [ALERTS_MAX_AGE_MS]. */
    fun addAlerts(id: String, lines: List<String>, now: Long, minor: Set<Int> = emptySet()) {
        if (lines.isEmpty()) return
        updateDevice(id) { d ->
            d.alerts = (d.alerts + lines.mapIndexed { i, l -> AlertEntry(now, l, i !in minor) }).filter { now - it.t <= ALERTS_MAX_AGE_MS }.takeLast(ALERTS_KEEP)
        }
    }


    /**
     * Plan 12 §2: a partner opened the report. Written at most every [VIEW_EVERY_MS], so a page left open or reloaded
     * doesn't rewrite the device file each time.
     */
    fun markViewed(deviceId: String, partnerId: String, now: Long) {
        val last = device(deviceId)?.partners?.firstOrNull { it.id == partnerId }?.lastViewMs ?: return
        if (now - last < VIEW_EVERY_MS) return
        updateDevice(deviceId) { dev -> dev.partners.firstOrNull { it.id == partnerId }?.lastViewMs = now }
    }

    /** An app device read its partner's alert feed. Written at most hourly: only "is it still reading" matters. */
    fun markFeedRead(deviceId: String, partnerId: String, partnerDeviceId: String, now: Long) {
        val pd = device(deviceId)?.partners?.firstOrNull { it.id == partnerId }?.devices?.firstOrNull { it.id == partnerDeviceId && it.kind == KIND_APP } ?: return
        if (now - pd.lastFeedMs < 3_600_000L) return
        updateDevice(deviceId) { dev -> dev.partners.firstOrNull { it.id == partnerId }?.devices?.firstOrNull { it.id == partnerDeviceId }?.lastFeedMs = now }
    }

    fun addOwnerNotice(id: String, text: String, now: Long, kind: String = "") = updateDevice(id) { d ->
        d.ownerNotices = (d.ownerNotices + AlertEntry(now, text, kind = kind)).filter { now - it.t <= ALERTS_MAX_AGE_MS }.takeLast(ALERTS_KEEP)
    }

    @Serializable
    data class SealedReport(val t: Long, val from: Long, val to: Long, val blob: String)

    private fun reportsFile(id: String) = File(root, "devices/$id.reports.jsonl")

    /** Stores one sealed report slice (ciphertext only, D-023), keeping the newest [REPORTS_KEEP] within [REPORTS_MAX_AGE_MS]. */
    fun saveReport(id: String, r: SealedReport, now: Long) = synchronized(lock(id)) {
        // The phone re-sends the slice it's still filling (same `from`, later `to`) at every sync: replace, don't add.
        // A shared screenshot is a zero-length slice (from == to) and is never replaced.
        val open = r.to > r.from
        val kept = (readReports(id).filterNot { open && it.from == r.from && it.to > it.from } + r).filter { now - it.to <= REPORTS_MAX_AGE_MS }.sortedBy { it.to }.takeLast(REPORTS_KEEP)
        writeAtomic(reportsFile(id), kept.joinToString("") { json.encodeToString(SealedReport.serializer(), it) + "\n" })
    }

    fun readReports(id: String): List<SealedReport> {
        if (!SAFE_ID.matches(id)) return emptyList()
        val f = reportsFile(id)
        if (!f.exists()) return emptyList()
        return f.useLines { seq -> seq.filter { it.isNotBlank() }.mapNotNull { runCatching { json.decodeFromString(SealedReport.serializer(), it) }.getOrNull() }.toList() }
    }

    /** D-023 migration: delete readable activity records left by earlier versions. Returns how many files went. */
    fun purgeLegacyRecords(): Int =
        File(root, "devices").listFiles { f -> f.name.contains(".activity.jsonl") }.orEmpty().count { it.delete() }

    companion object {
        const val REPORTS_KEEP = 240
        const val ALERTS_KEEP = 100
        const val VIEW_EVERY_MS = 5 * 60_000L
        const val ALERTS_MAX_AGE_MS = 14L * 24 * 3_600_000
        const val REPORTS_MAX_AGE_MS = 9L * 24 * 3_600_000
        const val REPORT_MAX_CHARS = 64 * 1024
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        const val KIND_APP = "app"
        const val KIND_WEB = "web"
        /** Plan 13: a partner's devices, phones and browsers together. */
        const val DEVICES_PER_PARTNER = 4
        /** An app that hasn't read the feed for this long no longer counts as where alerts go. */
        const val APP_QUIET_MS = 3L * 24 * 3_600_000
    }
}
