package app.focusfence.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Flat-file store, on purpose. Activity records are appended as JSON Lines in
 * the *same format the phone writes and exports* (docs/specs/event-log.md), one
 * file per device, so there is one schema end to end and an operator can read
 * the data with the same tools/analyze_logs.py. Device metadata is a small JSON
 * file. This is a prototype: swap in Postgres + object storage before anything
 * but personal/small-group use (see docs/specs/accountability-architecture.md).
 *
 * Encrypted screenshot blobs are opaque ciphertext — the server has no key and
 * cannot read them (see Ecies on the device).
 */
class Store(private val root: File) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        File(root, "devices").mkdirs()
        File(root, "blobs").mkdirs()
    }

    @Serializable
    data class Device(
        val id: String,
        val partnerEmail: String,
        val publicKeySpki: String,
        var expectedNextSeq: Long = 1,
        var lastHash: String = "GENESIS",
        var lastSeenMs: Long = 0,
        var enrolledMs: Long = 0,
    )

    private fun deviceFile(id: String) = File(root, "devices/$id.json")
    private fun recordsFile(id: String) = File(root, "devices/$id.activity.jsonl")
    private fun lock(id: String) = locks.computeIfAbsent(id) { Any() }

    fun createDevice(partnerEmail: String, publicKeySpki: String): Device {
        val id = java.util.UUID.randomUUID().toString()
        val d = Device(id, partnerEmail, publicKeySpki, enrolledMs = System.currentTimeMillis(),
            lastSeenMs = System.currentTimeMillis())
        saveDevice(d)
        return d
    }

    fun device(id: String): Device? =
        deviceFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Device.serializer(), it.readText()) }

    fun saveDevice(d: Device) = synchronized(lock(d.id)) {
        deviceFile(d.id).writeText(json.encodeToString(Device.serializer(), d))
    }

    fun allDevices(): List<Device> =
        File(root, "devices").listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { json.decodeFromString(Device.serializer(), it.readText()) } ?: emptyList()

    /** Appends raw JSONL records (already validated) and advances the device cursor. */
    fun appendRecords(d: Device, recordsRaw: String, lastSeq: Long, newHash: String) = synchronized(lock(d.id)) {
        recordsFile(d.id).appendText(recordsRaw.trimEnd('\n') + "\n")
        d.expectedNextSeq = lastSeq + 1
        d.lastHash = newHash
        d.lastSeenMs = System.currentTimeMillis()
        saveDevice(d)
    }

    fun touch(d: Device) = synchronized(lock(d.id)) {
        d.lastSeenMs = System.currentTimeMillis()
        saveDevice(d)
    }

    fun readRecords(id: String): List<Map<String, kotlinx.serialization.json.JsonElement>> {
        val f = recordsFile(id)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { json.parseToJsonElement(it).jvmMap() }.getOrNull()
        }
    }

    fun saveBlob(deviceId: String, shotId: String, bytes: ByteArray) {
        File(root, "blobs/${deviceId}_$shotId.bin").writeBytes(bytes)
    }
}

private fun kotlinx.serialization.json.JsonElement.jvmMap(): Map<String, kotlinx.serialization.json.JsonElement> =
    (this as kotlinx.serialization.json.JsonObject).toMap()
