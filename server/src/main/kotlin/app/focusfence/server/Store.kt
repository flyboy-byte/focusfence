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
 * invites) is a small JSON file per device. Mailboxes are opaque ciphertext.
 * Prototype: swap in Postgres + object storage before anything but personal or
 * small-group use.
 */
class Store(private val root: File) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        File(root, "devices").mkdirs()
        File(root, "blobs").mkdirs()
        File(root, "mailbox").mkdirs()
    }

    @Serializable
    data class Partner(
        val id: String,
        val name: String,
        val email: String,
        /**
         * Secret in the partner's private report link (/p/{token}). Kept in the
         * clear so alert emails can carry the link; it grants only that report.
         */
        val token: String,
        val linkedMs: Long,
        var phone: String? = null,
        var phoneVerified: Boolean = false,
        var smsCodeHash: String? = null,
        var smsCodeExpiresMs: Long = 0,
        var lastSmsMs: Long = 0,
    )

    @Serializable
    data class Invite(val codeHash: String, val expiresMs: Long)

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
    )

    @Serializable
    data class Mailbox(val tokenHash: String, val createdMs: Long, var lastWriteMs: Long = 0, var nextId: Long = 1)

    @Serializable
    data class MailMessage(val id: Long, val t: Long, val c: String)

    private fun deviceFile(id: String) = File(root, "devices/$id.json")
    private fun recordsFile(id: String) = File(root, "devices/$id.activity.jsonl")
    private fun mailboxFile(id: String) = File(root, "mailbox/$id.json")
    private fun mailFile(id: String) = File(root, "mailbox/$id.msgs.jsonl")
    fun lock(id: String): Any = locks.computeIfAbsent(id) { Any() }

    fun createDevice(publicKeySpki: String, now: Long): Device {
        val id = java.util.UUID.randomUUID().toString()
        val d = Device(id, publicKeySpki, enrolledMs = now, lastSeenMs = now, lastReportMs = now)
        saveDevice(d)
        return d
    }

    fun device(id: String): Device? {
        if (!SAFE_ID.matches(id)) return null
        return deviceFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Device.serializer(), it.readText()) }
    }

    fun saveDevice(d: Device) = synchronized(lock(d.id)) {
        deviceFile(d.id).writeText(json.encodeToString(Device.serializer(), d))
    }

    /** Read-modify-write under the device lock. */
    fun <T> updateDevice(id: String, block: (Device) -> T): T? = synchronized(lock(id)) {
        val d = device(id) ?: return null
        val r = block(d)
        saveDevice(d)
        r
    }

    fun allDevices(): List<Device> =
        File(root, "devices").listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { json.decodeFromString(Device.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()

    /** The device and partner a report-link token belongs to. */
    fun partnerByToken(token: String): Pair<Device, Partner>? {
        if (token.length < 20) return null
        for (d in allDevices()) {
            val p = d.partners.firstOrNull {
                java.security.MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray())
            } ?: continue
            return d to p
        }
        return null
    }

    /** Appends raw JSONL records (already validated) and advances the device cursor. */
    fun appendRecords(id: String, recordsRaw: String, lastSeq: Long, newHash: String, gap: Long, now: Long) =
        synchronized(lock(id)) {
            val d = device(id) ?: return@synchronized
            recordsFile(id).appendText(recordsRaw.trimEnd('\n') + "\n")
            d.expectedNextSeq = lastSeq + 1
            d.lastHash = newHash
            d.lastSeenMs = now
            d.staleAlerted = false
            d.missingRecords += gap
            saveDevice(d)
        }

    fun readRecords(id: String): List<String> {
        val f = recordsFile(id)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }
    }

    fun saveBlob(deviceId: String, shotId: String, bytes: ByteArray) {
        require(SAFE_ID.matches(shotId))
        File(root, "blobs/${deviceId}_$shotId.bin").writeBytes(bytes)
    }

    // ---- blind mailbox --------------------------------------------------------

    fun mailbox(id: String): Mailbox? =
        mailboxFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Mailbox.serializer(), it.readText()) }

    /** Creates the mailbox, or returns the existing one (the caller checks its token hash). */
    fun createMailbox(id: String, tokenHash: String, now: Long): Mailbox = synchronized(lock("mb:$id")) {
        mailbox(id) ?: Mailbox(tokenHash, now).also {
            mailboxFile(id).writeText(json.encodeToString(Mailbox.serializer(), it))
        }
    }

    fun postMail(id: String, ciphertextB64: String, now: Long): Long = synchronized(lock("mb:$id")) {
        val mb = mailbox(id) ?: error("no mailbox")
        val msg = MailMessage(mb.nextId, now, ciphertextB64)
        mailFile(id).appendText(json.encodeToString(MailMessage.serializer(), msg) + "\n")
        mb.nextId++
        mb.lastWriteMs = now
        mailboxFile(id).writeText(json.encodeToString(Mailbox.serializer(), mb))
        prune(id, now)
        msg.id
    }

    fun readMail(id: String, after: Long, limit: Int = 200): List<MailMessage> {
        val f = mailFile(id)
        if (!f.exists()) return emptyList()
        return f.readLines().asSequence().filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString(MailMessage.serializer(), it) }.getOrNull() }
            .filter { it.id > after }.take(limit).toList()
    }

    /** Keep the last [MAIL_KEEP] messages and nothing older than [MAIL_MAX_AGE_MS]. */
    private fun prune(id: String, now: Long) {
        val f = mailFile(id)
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.size <= MAIL_KEEP + 50) return
        val kept = lines.takeLast(MAIL_KEEP).filter {
            val t = runCatching { json.decodeFromString(MailMessage.serializer(), it).t }.getOrDefault(now)
            now - t <= MAIL_MAX_AGE_MS
        }
        f.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        const val MAIL_KEEP = 1000
        const val MAIL_MAX_AGE_MS = 30L * 24 * 3_600_000
    }
}
