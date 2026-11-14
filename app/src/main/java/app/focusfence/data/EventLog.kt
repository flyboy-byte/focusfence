package app.focusfence.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.focusfence.dns.LogJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only JSON-lines log on app-private storage, in two channels (see
 * [app.focusfence.dns.LogEvents] for what goes where):
 *
 *   files/logs/activity.jsonl (+ .1 … .3)   accountability/detection record
 *   files/logs/diag.jsonl     (+ .1 … .2)   battery, performance, errors
 *
 * Writes are batched in memory and flushed on a single background thread —
 * the DNS reader thread must never touch the disk. A flush happens after
 * [FLUSH_LINES] lines or [FLUSH_MS] (checked on the next append), and
 * immediately for `urgent` records (protection state changes), so a gap in
 * protection is on disk before anything can kill the process. The cost is that
 * up to [FLUSH_MS] of ordinary records can be lost if the process is killed;
 * process restarts are themselves logged, so that loss is visible.
 *
 * Every line gets a per-channel `seq` that only ever increases, persisted at
 * each flush. Lines are in write order, not strictly `t` order — UsageStats
 * events are pulled after the fact and carry their own earlier timestamps.
 *
 * Process-wide singleton: both services run in the app's single process.
 */
object EventLog {

    private const val TAG = "FocusFenceLog"
    private const val PREFS_NAME = "focusfence_log"
    private const val FLUSH_LINES = 200
    private const val FLUSH_MS = 30_000L

    enum class Channel(val base: String, val maxBytes: Long, val keep: Int) {
        ACTIVITY("activity", 8L * 1024 * 1024, 4),
        DIAG("diag", 4L * 1024 * 1024, 3),
    }

    private class Buffer {
        val lines = ArrayList<String>()
        var seq = 0L
        var lastFlushAt = 0L
    }

    private lateinit var appContext: Context
    private val buffers = Channel.values().associateWith { Buffer() }
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "focusfence-log-writer").apply { isDaemon = true }
    }

    /** Lines that could not be written; reported in the next diag flush. */
    private val droppedLines = AtomicLong(0)

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val p = prefs()
        val now = System.currentTimeMillis()
        for ((channel, buf) in buffers) {
            buf.seq = p.getLong(seqKey(channel), 0L)
            buf.lastFlushAt = now
        }
        initialized = true
    }

    fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun dir(): File = File(appContext.filesDir, "logs").apply { mkdirs() }

    fun activity(fields: Map<String, Any?>, urgent: Boolean = false) =
        append(Channel.ACTIVITY, fields, urgent)

    fun diag(fields: Map<String, Any?>, urgent: Boolean = false) =
        append(Channel.DIAG, fields, urgent)

    private fun append(channel: Channel, fields: Map<String, Any?>, urgent: Boolean) {
        if (!initialized) return
        val buf = buffers.getValue(channel)
        val shouldFlush: Boolean
        synchronized(buf) {
            buf.seq++
            val withSeq = LinkedHashMap<String, Any?>(fields.size + 1)
            withSeq["seq"] = buf.seq
            withSeq.putAll(fields)
            buf.lines += LogJson.encode(withSeq)
            shouldFlush = urgent || buf.lines.size >= FLUSH_LINES ||
                System.currentTimeMillis() - buf.lastFlushAt >= FLUSH_MS
        }
        if (shouldFlush) writer.execute { flushChannel(channel) }
    }

    /** Queues a flush of every channel. */
    fun flushAsync() {
        if (!initialized) return
        for (channel in Channel.values()) writer.execute { flushChannel(channel) }
    }

    /** Flushes every channel and waits. Call off the main thread (export, diagnostics). */
    fun flushBlocking() {
        if (!initialized) return
        writer.submit { Channel.values().forEach { flushChannel(it) } }.get()
    }

    /** Runs on the writer thread only. */
    private fun flushChannel(channel: Channel) {
        val buf = buffers.getValue(channel)
        val lines: List<String>
        val seq: Long
        synchronized(buf) {
            buf.lastFlushAt = System.currentTimeMillis()
            if (buf.lines.isEmpty()) return
            lines = ArrayList(buf.lines)
            buf.lines.clear()
            seq = buf.seq
        }
        try {
            val file = File(dir(), channel.base + ".jsonl")
            if (file.length() >= channel.maxBytes) rotate(channel)
            FileOutputStream(File(dir(), channel.base + ".jsonl"), true).bufferedWriter().use { w ->
                for (line in lines) {
                    w.write(line)
                    w.write("\n")
                }
            }
            prefs().edit().putLong(seqKey(channel), seq).apply()
        } catch (e: IOException) {
            droppedLines.addAndGet(lines.size.toLong())
            Log.w(TAG, "log write failed for ${channel.base}", e)
        }
    }

    private fun rotate(channel: Channel) {
        val d = dir()
        File(d, "${channel.base}.${channel.keep - 1}.jsonl").delete()
        for (i in channel.keep - 2 downTo 1) {
            File(d, "${channel.base}.$i.jsonl").renameTo(File(d, "${channel.base}.${i + 1}.jsonl"))
        }
        File(d, "${channel.base}.jsonl").renameTo(File(d, "${channel.base}.1.jsonl"))
    }

    /** Takes and resets the dropped-line count (reported by the diag perf window). */
    fun takeDropped(): Long = droppedLines.getAndSet(0)

    /** All files of [channel], oldest first. */
    fun files(channel: Channel): List<File> {
        val d = dir()
        val out = ArrayList<File>()
        for (i in channel.keep - 1 downTo 1) {
            File(d, "${channel.base}.$i.jsonl").takeIf { it.exists() }?.let { out += it }
        }
        File(d, "${channel.base}.jsonl").takeIf { it.exists() }?.let { out += it }
        return out
    }

    fun totalBytes(): Long = Channel.values().sumOf { c -> files(c).sumOf { it.length() } }

    /**
     * Streams [channel]'s lines oldest-first to [consumer]; a cheap substring
     * prefilter ([mustContain]) skips JSON decoding of irrelevant lines.
     */
    fun forEachLine(channel: Channel, mustContain: String? = null, consumer: (String) -> Unit) {
        for (file in files(channel)) {
            try {
                file.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        if (mustContain == null || line.contains(mustContain)) consumer(line)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "reading ${file.name}", e)
            }
        }
    }

    /** Deletes every log file. Sequence numbers keep counting, so a reset is visible. */
    fun clearBlocking() {
        if (!initialized) return
        writer.submit {
            for (c in Channel.values()) {
                synchronized(buffers.getValue(c)) { buffers.getValue(c).lines.clear() }
                files(c).forEach { it.delete() }
            }
        }.get()
    }

    private fun seqKey(channel: Channel) = "seq_" + channel.base
}
