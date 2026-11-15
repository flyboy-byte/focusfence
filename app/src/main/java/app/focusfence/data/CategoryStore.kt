package app.focusfence.data

import android.content.Context
import app.focusfence.dns.Categories
import app.focusfence.dns.HostsParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Content-category lists (adult, gambling, …) and their per-category policy
 * (OFF / REPORT / BLOCK). Classification is kept apart from policy on purpose:
 * a downloaded list labels a domain; the policy decides what happens. A list
 * downloaded here is parsed with the same [HostsParser] as the ad list and
 * cached per category in private storage.
 *
 * Policy and the reload signal ([KEY_VERSION]) live in the `focusfence` prefs
 * file so the VPN service's existing listener reloads on change. The list
 * contents never go to prefs (too large); only the version counter does, bumped
 * after a successful download, mirroring `AdListStore`'s KEY_COUNT pattern.
 */
object CategoryStore {

    const val KEY_VERSION = "category_version"
    private const val KEY_POLICY_PREFIX = "catpolicy_"
    private const val KEY_URL_PREFIX = "caturl_"

    private fun file(context: Context, id: String) = File(context.filesDir, "category_$id.txt")

    fun policy(context: Context, id: String): Categories.Policy =
        Categories.Policy.parse(BlocklistStore.prefs(context).getString(KEY_POLICY_PREFIX + id, null))

    fun setPolicy(context: Context, id: String, policy: Categories.Policy) {
        BlocklistStore.prefs(context).edit()
            .putString(KEY_POLICY_PREFIX + id, policy.name)
            .putLong(KEY_VERSION, System.currentTimeMillis()) // reload signal, written last
            .apply()
    }

    fun url(context: Context, source: Categories.Source): String =
        BlocklistStore.prefs(context).getString(KEY_URL_PREFIX + source.id, null) ?: source.url

    fun setUrl(context: Context, id: String, url: String) {
        BlocklistStore.prefs(context).edit().putString(KEY_URL_PREFIX + id, url).apply()
    }

    /** All configured policies, for [Categories.decide]. */
    fun policies(context: Context): Map<String, Categories.Policy> =
        Categories.DEFAULT_SOURCES.associate { it.id to policy(context, it.id) }

    /**
     * Loads every category whose policy is not OFF into memory. Called off the
     * main thread by the VPN service. Categories with no downloaded file yet
     * simply contribute nothing.
     */
    fun loadActive(context: Context): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for (source in Categories.DEFAULT_SOURCES) {
            if (policy(context, source.id) == Categories.Policy.OFF) continue
            val f = file(context, source.id)
            if (!f.exists()) continue
            out[source.id] = try {
                f.useLines { HostsParser.parse(it) }
            } catch (e: IOException) {
                continue
            }
        }
        return out
    }

    /** Downloads one category list. Blocking; returns the entry count. Bumps [KEY_VERSION] last. */
    @Throws(IOException::class)
    fun download(context: Context, source: Categories.Source): Int {
        val target = url(context, source)
        if (target.isBlank()) throw IOException("No list URL set for ${source.label}")
        val tmp = File(context.filesDir, "category_${source.id}.tmp")
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val count = tmp.useLines { HostsParser.parse(it) }.size
        if (count == 0) {
            tmp.delete()
            throw IOException("List for ${source.label} contained no entries")
        }
        if (!tmp.renameTo(file(context, source.id))) {
            file(context, source.id).delete()
            if (!tmp.renameTo(file(context, source.id))) throw IOException("Could not save ${source.label} list")
        }
        BlocklistStore.prefs(context).edit().putLong(KEY_VERSION, System.currentTimeMillis()).apply()
        return count
    }
}
