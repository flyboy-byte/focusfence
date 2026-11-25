package app.focusfence.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.focusfence.dns.Categories
import java.util.concurrent.TimeUnit

/**
 * Keeps the block lists fresh without a button per list (2.24). Once a day, on Wi-Fi (unmetered), any list that is on
 * and older than [STALE_MS] is downloaded again. A list that is on but was never downloaded is fetched on any network,
 * once, so turning a list on never leaves it empty until the next Wi-Fi.
 */
object ListRefresh {
    const val STALE_MS = 7L * 24 * 3_600_000
    private const val DAILY = "focusfence_list_refresh"
    private const val MISSING = "focusfence_list_missing"
    const val KEY_STALE = "stale"

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        wm.enqueueUniquePeriodicWork(
            DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ListRefreshWorker>(1, TimeUnit.DAYS)
                .setInputData(androidx.work.workDataOf(KEY_STALE to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build(),
        )
        if (enabled(c).any { !CategoryStore.file(c, it.id).exists() }) {
            wm.enqueueUniqueWork(
                MISSING, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ListRefreshWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }

    fun enabled(c: Context): List<Categories.Bundle> =
        Categories.BUNDLES.filter { CategoryStore.policy(c, it.id) != Categories.Policy.OFF && CategoryStore.url(c, it).isNotBlank() }

    /** When the stalest list that is on was downloaded; null if one of them never was. */
    fun oldestUpdate(c: Context): Long? {
        val files = enabled(c).map { CategoryStore.file(c, it.id) }
        if (files.isEmpty() || files.any { !it.exists() }) return null
        return files.minOf { it.lastModified() }
    }

    /** The lists to fetch now: missing ones always, stale ones too when [includeStale]. */
    fun due(c: Context, now: Long, includeStale: Boolean): List<Categories.Bundle> = enabled(c).filter {
        val f = CategoryStore.file(c, it.id)
        !f.exists() || (includeStale && now - f.lastModified() >= STALE_MS)
    }
}

class ListRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = applicationContext
        EventLog.init(c)
        // The one-off run (any network) only fills in missing lists; the daily one (Wi-Fi) also refreshes stale ones.
        val daily = inputData.getBoolean(ListRefresh.KEY_STALE, false)
        var failed = 0
        for (b in ListRefresh.due(c, System.currentTimeMillis(), includeStale = daily)) {
            try { CategoryStore.download(c, b) } catch (e: Exception) { failed++ }
        }
        app.focusfence.monitor.Monitor.perf.count("list_refresh")
        if (failed > 0) app.focusfence.monitor.Monitor.perf.count("list_refresh_fail", failed.toLong())
        return Result.success()
    }
}
