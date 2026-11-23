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
class Store(private val root: File, private val shotBytesPerDevice: Long = SHOT_BYTES_PER_DEVICE) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Any>()
    val dir: File get() = root

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
        /** False until the address owner clicks the link we emailed. Nothing but that one email goes to an unconfirmed address. */
        var confirmed: Boolean = true,
        /**
         * SHA-256 of the single-use token in the confirmation email. Only the hash is kept, and the token itself is
         * only ever in that email: it is never in a redirect or a response, so the person who typed the address in
         * can't confirm it for its owner. Null once confirmed (or for partners created before double opt-in).
         */
        var confirmHash: String? = null,
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
    fun mailboxCount(): Int = File(root, "mailbox").listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".msgs.jsonl") }?.size ?: 0

    /** Read-modify-write under the device lock. */
    fun <T> updateDevice(id: String, block: (Device) -> T): T? = synchronized(lock(id)) {
        val d = device(id) ?: return null
        val r = block(d)
        saveDevice(d)
        r
    }

    /** Removes a device, its records and its screenshots. Only the admin page calls this. */
    fun deleteDevice(id: String): Boolean = synchronized(lock(id)) {
        if (!SAFE_ID.matches(id) || !deviceFile(id).exists()) return false
        deviceFile(id).delete(); recordsFile(id).delete()
        File(root, "blobs").listFiles { f -> f.name.startsWith(id + "_") }?.forEach { it.delete() }
        true
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

    /** The device and unconfirmed partner a confirmation-email token belongs to (constant-time hash compare). */
    fun partnerByConfirmToken(token: String): Pair<Device, Partner>? {
        if (token.length < 20) return null
        val want = Integrity.sha256(token).toByteArray()
        for (d in allDevices()) {
            val p = d.partners.firstOrNull { it.confirmHash != null && java.security.MessageDigest.isEqual(it.confirmHash!!.toByteArray(), want) } ?: continue
            return d to p
        }
        return null
    }

    fun partnerById(id: String): Pair<Device, Partner>? {
        for (d in allDevices()) d.partners.firstOrNull { it.id == id }?.let { return d to it }
        return null
    }

    /** Appends raw JSONL records (already validated) and advances the device cursor. */
    fun appendRecords(id: String, recordsRaw: String, lastSeq: Long, newHash: String, gap: Long, now: Long) =
        synchronized(lock(id)) {
            val d = device(id) ?: return@synchronized
            recordsFile(id).appendText(recordsRaw.trimEnd('\n') + "\n")
            rotateRecords(recordsFile(id))
            d.expectedNextSeq = lastSeq + 1
            d.lastHash = newHash
            d.lastSeenMs = now
            d.staleAlerted = false
            d.missingRecords += gap
            saveDevice(d)
        }

    /** Streams the file (never `readLines` on a whole file: the heap is small and the file is attacker-influenced). */
    fun readRecords(id: String): List<String> {
        val f = recordsFile(id)
        if (!f.exists()) return emptyList()
        return f.useLines { seq -> seq.filter { it.isNotBlank() }.toList() }
    }

    /** Past [RECORDS_MAX_BYTES], drop the oldest lines so about [RECORDS_KEEP_BYTES] of the newest remain. */
    private fun rotateRecords(f: File) {
        if (f.length() <= RECORDS_MAX_BYTES) return
        val tail = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(f.length() - RECORDS_KEEP_BYTES)
            raf.readLine() // discard the partial line we landed in
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) { val n = raf.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            out.toByteArray()
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(tail)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun saveBlob(deviceId: String, shotId: String, bytes: ByteArray) {
        require(SAFE_ID.matches(deviceId) && SAFE_ID.matches(shotId))
        require(bytes.size <= SHOT_MAX_BYTES) { "shot too large" }
        File(root, "blobs/${deviceId}_$shotId.bin").writeBytes(bytes)
        // Keep only the newest SHOTS_PER_DEVICE per device, so a signed-in client can't fill the disk.
        val mine = File(root, "blobs").listFiles { f -> f.name.startsWith("${deviceId}_") }.orEmpty()
        // ...and no more than shotBytesPerDevice, oldest first.
        val byAge = mine.filter { it.exists() }.sortedBy { it.lastModified() }.toMutableList()
        var total = byAge.sumOf { it.length() }
        while (byAge.size > 1 && (byAge.size > SHOTS_PER_DEVICE || total > shotBytesPerDevice)) {
            val oldest = byAge.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    // ---- blind mailbox --------------------------------------------------------

    fun mailbox(id: String): Mailbox? =
        mailboxFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Mailbox.serializer(), it.readText()) }

    /** Creates the mailbox, or returns the existing one (the caller checks its token hash). */
    fun createMailbox(id: String, tokenHash: String, now: Long): Mailbox = synchronized(lock("mb:$id")) {
        mailbox(id) ?: Mailbox(tokenHash, now).also {
            writeAtomic(mailboxFile(id), json.encodeToString(Mailbox.serializer(), it))
        }
    }

    fun postMail(id: String, ciphertextB64: String, now: Long): Long = synchronized(lock("mb:$id")) {
        val mb = mailbox(id) ?: error("no mailbox")
        val msg = MailMessage(mb.nextId, now, ciphertextB64)
        mailFile(id).appendText(json.encodeToString(MailMessage.serializer(), msg) + "\n")
        mb.nextId++
        mb.lastWriteMs = now
        writeAtomic(mailboxFile(id), json.encodeToString(Mailbox.serializer(), mb))
        if (msg.id % 25 == 0L) prune(id, now) // not on every post: pruning reads the file
        msg.id
    }

    fun readMail(id: String, after: Long, limit: Int = 200): List<MailMessage> {
        val f = mailFile(id)
        if (!f.exists()) return emptyList()
        return f.useLines { seq ->
            seq.filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString(MailMessage.serializer(), it) }.getOrNull() }
                .filter { it.id > after }.take(limit).toList()
        }
    }

    /** Keep the last [MAIL_KEEP] messages and nothing older than [MAIL_MAX_AGE_MS]. */
    private fun prune(id: String, now: Long) {
        val f = mailFile(id)
        val lines = f.useLines { seq -> seq.filter { it.isNotBlank() }.toList() }
        if (lines.size <= MAIL_KEEP + 25) return
        val kept = lines.takeLast(MAIL_KEEP).filter {
            val t = runCatching { json.decodeFromString(MailMessage.serializer(), it).t }.getOrDefault(now)
            now - t <= MAIL_MAX_AGE_MS
        }
        writeAtomic(f, kept.joinToString("\n", postfix = "\n"))
    }

    companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        const val MAIL_KEEP = 300
        const val RECORDS_MAX_BYTES = 16L * 1024 * 1024
        const val RECORDS_KEEP_BYTES = 10L * 1024 * 1024
        const val SHOT_MAX_BYTES = 2 * 1024 * 1024
        const val SHOTS_PER_DEVICE = 200
        const val SHOT_BYTES_PER_DEVICE = 64L * 1024 * 1024
        const val MAIL_MAX_AGE_MS = 30L * 24 * 3_600_000
    }
}
