package app.focusfence.data

import android.content.Context
import app.focusfence.dns.FilterSource
import app.focusfence.dns.FilterSources
import app.focusfence.dns.HostsParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the downloadable filter lists (W11): one file and one enabled flag per
 * [FilterSource], all downloaded at runtime from each maintainer's own hosting.
 * Lists are never bundled — see `docs/research/2026-08-17-filter-list-licensing-claude.md`
 * for why that distinction is load-bearing (StevenBlack's base list carries
 * non-commercial upstream data).
 *
 * Change signal: every mutation bumps [KEY_REV] *last* (the same pattern the
 * pre-W11 store used with its count key), and the VPN service's prefs listener
 * reloads on [KEY_REV]/[KEY_ENABLED_SOURCES]. Updates are user-triggered only —
 * no periodic job, no new wakeup; battery is a product requirement.
 */
object FilterListStore {

    const val KEY_ENABLED_SOURCES = "filter_enabled_sources"
    const val KEY_REV = "filter_rev"
    private const val KEY_MIGRATED = "filter_migrated_v1"

    // Pre-W11 single-list keys, kept only for migration.
    private const val LEGACY_KEY_ENABLED = "adblock_enabled"
    private const val LEGACY_KEY_UPDATED_AT = "adblock_updated_at"
    private const val LEGACY_KEY_COUNT = "adblock_count"
    private const val LEGACY_FILE_NAME = "adblock_hosts.txt"

    private fun countKey(id: String) = "filter_${id}_count"
    private fun updatedKey(id: String) = "filter_${id}_updated_at"

    fun file(context: Context, id: String): File = File(context.filesDir, "filter_$id.txt")

    /**
     * Folds the pre-W11 single StevenBlack list into the per-source scheme so an
     * upgrade keeps the user's downloaded list and enabled state. Idempotent;
     * called once from Application.onCreate before any service reads the store.
     */
    fun ensureMigrated(context: Context) {
        val prefs = BlocklistStore.prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacyFile = File(context.filesDir, LEGACY_FILE_NAME)
        val editor = prefs.edit()
        if (legacyFile.exists()) {
            legacyFile.renameTo(file(context, FilterSources.LEGACY_ID))
            editor.putLong(updatedKey(FilterSources.LEGACY_ID), prefs.getLong(LEGACY_KEY_UPDATED_AT, 0L))
            editor.putInt(countKey(FilterSources.LEGACY_ID), prefs.getInt(LEGACY_KEY_COUNT, 0))
        }
        if (prefs.getBoolean(LEGACY_KEY_ENABLED, false)) {
            editor.putStringSet(KEY_ENABLED_SOURCES, setOf(FilterSources.LEGACY_ID))
        }
        editor.remove(LEGACY_KEY_ENABLED)
            .remove(LEGACY_KEY_UPDATED_AT)
            .remove(LEGACY_KEY_COUNT)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    fun enabledIds(context: Context): Set<String> =
        BlocklistStore.prefs(context).getStringSet(KEY_ENABLED_SOURCES, emptySet())!!
            .filter { FilterSources.byId(it) != null }.toSet()

    fun isEnabled(context: Context, id: String): Boolean = id in enabledIds(context)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        if (FilterSources.byId(id) == null) return
        val current = enabledIds(context)
        val next = if (enabled) current + id else current - id
        if (next == current) return
        BlocklistStore.prefs(context).edit()
            .putStringSet(KEY_ENABLED_SOURCES, next)
            .putInt(KEY_REV, rev(context) + 1)
            .apply()
    }

    fun rev(context: Context): Int = BlocklistStore.prefs(context).getInt(KEY_REV, 0)

    fun entryCount(context: Context, id: String): Int =
        BlocklistStore.prefs(context).getInt(countKey(id), 0)

    fun updatedAt(context: Context, id: String): Long =
        BlocklistStore.prefs(context).getLong(updatedKey(id), 0L)

    /** Enabled sources whose list has never been downloaded (nothing to enforce). */
    fun enabledButMissing(context: Context): List<String> =
        enabledIds(context).filter { !file(context, it).exists() }

    /**
     * Builds host -> source id over all enabled sources. Catalog order decides
     * provenance for hosts several lists share, and passing the catalog's own id
     * instance as the value means the map holds one string per source, not one
     * per entry. Blocking file I/O — call from a background thread.
     */
    fun loadEnabled(context: Context): Map<String, String> {
        val enabled = enabledIds(context)
        if (enabled.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(1 shl 18)
        for (source in FilterSources.ALL) {
            if (source.id !in enabled) continue
            val f = file(context, source.id)
            if (!f.exists()) continue
            val hosts = try {
                f.useLines { HostsParser.parse(it) }
            } catch (e: IOException) {
                continue
            }
            for (host in hosts) out.putIfAbsent(host, source.id)
        }
        return out
    }

    /**
     * Downloads one source's list. Blocking — call from a background thread.
     * Returns the entry count. The tmp-then-rename keeps a half-written download
     * from ever replacing a good list, and [KEY_REV] is written last as the
     * reload signal.
     */
    @Throws(IOException::class)
    fun download(context: Context, source: FilterSource): Int {
        val target = file(context, source.id)
        val tmp = File(context.filesDir, "${target.name}.tmp")
        val conn = URL(source.url).openConnection() as HttpURLConnection
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
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save list")
        }
        BlocklistStore.prefs(context).edit()
            .putLong(updatedKey(source.id), System.currentTimeMillis())
            .putInt(countKey(source.id), count)
            .putInt(KEY_REV, rev(context) + 1)
            .apply()
        return count
    }
}
