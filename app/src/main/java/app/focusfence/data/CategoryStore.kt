package app.focusfence.data

import android.content.Context
import app.focusfence.dns.Categories
import app.focusfence.dns.Categories.Bundle
import app.focusfence.dns.HostsParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Curated filter bundles (see [Categories.BUNDLES]) and their per-bundle policy
 * (OFF / REPORT / BLOCK). A downloaded list labels a domain; the policy decides
 * what happens. Lists are parsed with the same [HostsParser] as the ad list and
 * cached per bundle in private storage.
 *
 * Policy, the base tier, and the reload signal ([KEY_VERSION]) live in the
 * `focusfence` prefs file so the VPN service's existing listener reloads on
 * change. List contents never go to prefs (too large); only the version counter
 * does, bumped after a successful download, mirroring `AdListStore`'s pattern.
 *
 * Security-class bundles default to BLOCK ([Bundle.defaultPolicy]) even with no
 * stored setting, so they are on out of the box once their list downloads.
 */
object CategoryStore {

    const val KEY_VERSION = "category_version"
    private const val KEY_POLICY_PREFIX = "catpolicy_"
    private const val KEY_URL_PREFIX = "caturl_"
    private const val KEY_COUNT_PREFIX = "catcount_"
    private const val KEY_FULL_PREFIX = "catfull_"
    private const val KEY_BASE_TIER = "category_base_tier"

    fun file(context: Context, id: String) = File(context.filesDir, "category_$id.txt")

    fun entryCount(context: Context, id: String): Int =
        BlocklistStore.prefs(context).getInt(KEY_COUNT_PREFIX + id, 0)

    /** Stored policy, or the bundle's [Bundle.defaultPolicy] if the owner hasn't set one. */
    fun policy(context: Context, id: String): Categories.Policy {
        val bundle = Categories.bundle(id)
        val stored = BlocklistStore.prefs(context).getString(KEY_POLICY_PREFIX + id, null)
            ?: return bundle?.defaultPolicy ?: Categories.Policy.OFF
        return Categories.effectivePolicy(bundle, Categories.Policy.parse(stored))
    }

    fun setPolicy(context: Context, id: String, policy: Categories.Policy) {
        BlocklistStore.prefs(context).edit()
            .putString(KEY_POLICY_PREFIX + id, policy.name)
            .putLong(KEY_VERSION, System.currentTimeMillis()) // reload signal, written last
            .apply()
    }

    fun baseTier(context: Context): Categories.BaseTier =
        Categories.BaseTier.parse(BlocklistStore.prefs(context).getString(KEY_BASE_TIER, null))

    fun setBaseTier(context: Context, tier: Categories.BaseTier) {
        // Tier change means the security list must be re-fetched; the caller triggers it.
        BlocklistStore.prefs(context).edit().putString(KEY_BASE_TIER, tier.name).apply()
    }

    fun useFull(context: Context, id: String): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_FULL_PREFIX + id, false)

    fun setUseFull(context: Context, id: String, full: Boolean) {
        BlocklistStore.prefs(context).edit().putBoolean(KEY_FULL_PREFIX + id, full).apply()
    }

    /**
     * The download URL for [bundle]: a user override if set, else the base tier
     * (tiered bundles), else the full or mini variant per [useFull].
     */
    fun url(context: Context, bundle: Bundle): String {
        BlocklistStore.prefs(context).getString(KEY_URL_PREFIX + bundle.id, null)?.let { return it }
        if (bundle.tiered) return baseTier(context).url
        return if (useFull(context, bundle.id) && bundle.fullUrl != null) bundle.fullUrl else bundle.url
    }

    fun setUrl(context: Context, id: String, url: String?) {
        BlocklistStore.prefs(context).edit().apply {
            if (url.isNullOrBlank()) remove(KEY_URL_PREFIX + id) else putString(KEY_URL_PREFIX + id, url)
        }.apply()
    }

    /** All bundle policies, for [Categories.decide]. */
    fun policies(context: Context): Map<String, Categories.Policy> =
        Categories.BUNDLES.associate { it.id to policy(context, it.id) }

    /**
     * Loads every bundle whose effective policy is not OFF into memory. Called
     * off the main thread by the VPN service. Bundles with no downloaded file
     * yet simply contribute nothing (until [ensureDefaultsDownloaded] fetches).
     */
    fun loadActive(context: Context): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for (bundle in Categories.BUNDLES) {
            if (policy(context, bundle.id) == Categories.Policy.OFF) continue
            val f = file(context, bundle.id)
            if (!f.exists()) continue
            out[bundle.id] = try {
                f.useLines { HostsParser.parse(it) }
            } catch (e: IOException) {
                continue
            }
        }
        return out
    }

    /**
     * Downloads any enabled bundle (policy != OFF) whose list is missing, so the
     * default-on security bundles take effect without the owner opening the
     * screen. Blocking; call off the main thread (the VPN service's category
     * reload thread does). Returns how many bundles were fetched.
     */
    fun ensureDefaultsDownloaded(context: Context): Int {
        var fetched = 0
        for (bundle in Categories.BUNDLES) {
            if (policy(context, bundle.id) == Categories.Policy.OFF) continue
            if (file(context, bundle.id).exists()) continue
            if (url(context, bundle).isBlank()) continue
            try {
                download(context, bundle)
                fetched++
            } catch (e: IOException) {
                // Offline or list moved; try again on the next reload.
            }
        }
        return fetched
    }

    /** Downloads one bundle's list. Blocking; returns the entry count. Bumps [KEY_VERSION] last. */
    @Throws(IOException::class)
    fun download(context: Context, bundle: Bundle): Int {
        val target = url(context, bundle)
        if (target.isBlank()) throw IOException("No list URL set for ${bundle.label}")
        val tmp = File(context.filesDir, "category_${bundle.id}.tmp")
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
            throw IOException("List for ${bundle.label} contained no entries")
        }
        if (!tmp.renameTo(file(context, bundle.id))) {
            file(context, bundle.id).delete()
            if (!tmp.renameTo(file(context, bundle.id))) throw IOException("Could not save ${bundle.label} list")
        }
        BlocklistStore.prefs(context).edit()
            .putInt(KEY_COUNT_PREFIX + bundle.id, count)
            .putLong(KEY_VERSION, System.currentTimeMillis())
            .apply()
        return count
    }
}
