package app.focusfence.data

import android.content.Context
import app.focusfence.dns.HostsParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the downloadable ad/tracker filter database (hosts-file format),
 * kept in the app's private storage.
 */
object AdListStore {

    /** StevenBlack's unified hosts list: ads + malware, aggregated from the same
     *  upstream sources AdGuard/uBlock use, maintained and hosts-format. */
    const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    const val KEY_ENABLED = "adblock_enabled"
    const val KEY_UPDATED_AT = "adblock_updated_at"
    const val KEY_COUNT = "adblock_count"

    private const val FILE_NAME = "adblock_hosts.txt"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun isEnabled(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun entryCount(context: Context): Int =
        BlocklistStore.prefs(context).getInt(KEY_COUNT, 0)

    fun updatedAt(context: Context): Long =
        BlocklistStore.prefs(context).getLong(KEY_UPDATED_AT, 0L)

    /** Loads the downloaded database into memory; empty set if none downloaded yet. */
    fun load(context: Context): Set<String> {
        val f = file(context)
        if (!f.exists()) return emptySet()
        return try {
            f.useLines { HostsParser.parse(it) }
        } catch (e: IOException) {
            emptySet()
        }
    }

    /**
     * Downloads the filter list. Blocking — call from a background thread.
     * Returns the number of entries. Writing [KEY_COUNT] last also serves as the
     * change signal the VPN service listens for to reload the list.
     */
    @Throws(IOException::class)
    fun download(context: Context): Int {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        val conn = URL(HOSTS_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        val count = tmp.useLines { HostsParser.parse(it) }.size
        if (count == 0) {
            tmp.delete()
            throw IOException("Downloaded list contained no entries")
        }
        if (!tmp.renameTo(file(context))) {
            file(context).delete()
            if (!tmp.renameTo(file(context))) throw IOException("Could not save list")
        }
        BlocklistStore.prefs(context).edit()
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .putInt(KEY_COUNT, count)
            .apply()
        return count
    }
}
