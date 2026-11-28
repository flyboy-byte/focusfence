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
    private const val WRITER_THREAD = "focusfence-log-writer"
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, WRITER_THREAD).apply { isDaemon = true }
    }
    private val repeats = app.focusfence.dns.Repeats()

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

    /** A diag error that can recur on every retry: written when its text changes, else once per six hours. */
    fun errorOnce(component: String, what: String, detail: String) {
        val now = System.currentTimeMillis()
        if (repeats.allow("$component:$what", detail, now)) diag(app.focusfence.dns.LogEvents.error(now, component, what, detail))
    }

    /** For a process that is about to die: writes [fields] and everything buffered, waiting at most two seconds. */
    fun diagBeforeDeath(fields: Map<String, Any?>) {
        if (!initialized) return
        append(Channel.DIAG, fields, urgent = false)
        val flushAll = Runnable { Channel.values().forEach { flushChannel(it) } }
        // On the writer thread itself (it crashed), waiting for it would never return.
        if (Thread.currentThread().name == WRITER_THREAD) flushAll.run()
        else writer.submit(flushAll).get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

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

    /**
     * Like [forEachLine], but for sync: skips whole files whose last line is at
     * or before [afterSeq] / older than [sinceT] (read from the file's tail, so
     * already-uploaded history costs nothing), and stops when [consumer]
     * returns false. Battery: this runs every 15 minutes.
     */
    fun scan(channel: Channel, afterSeq: Long = -1, sinceT: Long = Long.MIN_VALUE, consumer: (String) -> Boolean) {
        for (file in files(channel)) {
            val last = lastLine(file)
            if (last != null) {
                val lastSeq = seqOf(last)
                if (lastSeq != null && lastSeq <= afterSeq) continue
                val lastT = LogJson.peekT(last)
                if (lastT != null && lastT < sinceT) continue
            }
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) if (!consumer(line)) return
                }
            } catch (e: IOException) {
                Log.w(TAG, "reading ${file.name}", e)
            }
        }
    }

    /** The newest seq handed out on [channel] (flushed or not). */
    fun currentSeq(channel: Channel): Long {
        if (!initialized) return 0
        val buf = buffers.getValue(channel)
        return synchronized(buf) { buf.seq }
    }

    private val SEQ_PREFIX = Regex("^\\{\"seq\":(\\d+)")

    /** Every line starts with `{"seq":N` ([append] puts it first), so no decode is needed. */
    fun seqOf(line: String): Long? = SEQ_PREFIX.find(line)?.groupValues?.get(1)?.toLongOrNull()

    private fun lastLine(file: File): String? = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len == 0L) return null
            val n = minOf(len, 8192L).toInt()
            val buf = ByteArray(n)
            raf.seek(len - n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8).trimEnd('\n').substringAfterLast('\n')
        }
    } catch (e: IOException) {
        null
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
