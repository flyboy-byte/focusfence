package app.focusfence

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import app.focusfence.dns.GuardPolicy
import app.focusfence.data.AdListStore
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.EventLog
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.data.LogSettings
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityDiagnosticsBinding
import app.focusfence.dns.LogEvents
import app.focusfence.dns.LogJson
import app.focusfence.dns.LogSummary
import app.focusfence.monitor.Monitor
import app.focusfence.monitor.UsageCollector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Logs & diagnostics: what is being recorded, a summary of both logs (battery,
 * resolver health, detection counts, integrity trail), and export/clear.
 *
 * Turning recording off or clearing the log weakens accountability, so both
 * respect strict mode like every other loosening action, and both are
 * themselves logged.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.refreshButton.setOnClickListener { refresh() }
        binding.exportButton.setOnClickListener { export() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.usageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        bindSwitches()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindSwitches() {
        bindSwitch(binding.logAllowedSwitch, LogSettings.logAllowedDns(this), "log_allowed_dns") {
            LogSettings.setLogAllowedDns(this, it)
        }
        bindSwitch(binding.logUrlsSwitch, LogSettings.logUrls(this), "log_urls") { on ->
            LogSettings.setLogUrls(this, on)
            if (on && !UiUtil.isUrlFilterEnabled(this)) UiUtil.promptEnableUrlFilter(this)
        }
        binding.usageAccessText.setText(
            if (UsageCollector.hasAccess(this)) R.string.diag_usage_granted else R.string.diag_usage_missing
        )
    }

    /** Turning a recording switch off is a loosening action: strict mode applies, and it is logged. */
    private fun bindSwitch(
        sw: CompoundButton,
        value: Boolean,
        name: String,
        apply: (Boolean) -> Unit,
    ) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked ->
            fun persist(on: Boolean) {
                apply(on)
                EventLog.activity(
                    LogEvents.service(System.currentTimeMillis(), "log", "setting", "$name=$on"),
                    urgent = true,
                )
            }
            if (!checked) {
                // Turning a recording switch off is a loosening action. Revert the
                // visual now; the guard re-applies it only once satisfied.
                bindSwitches()
                UiUtil.guard(this, GuardPolicy.Action.DISABLE_LOGGING) { persist(false); bindSwitches() }
                return@setOnCheckedChangeListener
            }
            persist(true)
        }
    }

    private fun refresh() {
        binding.summaryText.setText(R.string.diag_loading)
        val app = applicationContext
        Thread {
            val text = try {
                Monitor.refreshBlocking(app)
                EventLog.flushBlocking()
                buildSummary().render()
            } catch (e: Exception) {
                getString(R.string.diag_error, e.toString())
            }
            val size = Formatter.formatShortFileSize(app, EventLog.totalBytes())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.summaryText.text = text
                binding.storageText.text = getString(R.string.diag_storage, size)
            }
        }.start()
    }

    /**
     * Diag in full (a few hundred small lines a day), activity for the last
     * [ACTIVITY_WINDOW_MS] only: the activity log can reach ~32 MB, and old
     * lines are skipped by timestamp without decoding. The full history is in
     * the export, for tools/analyze_logs.py.
     */
    private fun buildSummary(): LogSummary {
        val summary = LogSummary()
        EventLog.forEachLine(EventLog.Channel.DIAG) { line -> LogJson.decode(line)?.let(summary::add) }
        val since = System.currentTimeMillis() - ACTIVITY_WINDOW_MS
        EventLog.forEachLine(EventLog.Channel.ACTIVITY) { line ->
            val t = LogJson.peekT(line)
            if (t != null && t >= since) LogJson.decode(line)?.let(summary::add)
        }
        return summary
    }

    private companion object {
        const val ACTIVITY_WINDOW_MS = 24 * 60 * 60_000L
    }

    private fun export() {
        binding.exportButton.isEnabled = false
        val app = applicationContext
        Thread {
            val result = try {
                Monitor.refreshBlocking(app)
                EventLog.flushBlocking()
                Result.success(writeExportZip())
            } catch (e: IOException) {
                Result.failure(e)
            }
            runOnUiThread {
                binding.exportButton.isEnabled = true
                result.onSuccess { share(it) }.onFailure {
                    Toast.makeText(this, getString(R.string.diag_error, it.toString()), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** meta.json + summary.txt + every log file, as one zip in cache/export/. */
    private fun writeExportZip(): File {
        val dir = File(cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the latest export
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val zipFile = File(dir, "focusfence-logs-$stamp.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write((LogJson.encode(meta()) + "\n").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.txt"))
            zip.write(buildSummary().render().toByteArray())
            zip.closeEntry()
            for (channel in EventLog.Channel.values()) {
                for (file in EventLog.files(channel)) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        EventLog.activity(
            LogEvents.service(System.currentTimeMillis(), "log", "exported", zipFile.name),
            urgent = true,
        )
        return zipFile
    }

    /** Context needed to interpret the logs: device, versions, configuration. */
    private fun meta(): Map<String, Any?> {
        val pm = getSystemService(PowerManager::class.java)
        return linkedMapOf(
            "type" to LogEvents.TYPE_META,
            "t" to System.currentTimeMillis(),
            "schema" to LogEvents.SCHEMA_VERSION,
            "app_version" to BuildConfigCompat.versionName(application),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "rules" to BlocklistStore.rules(this).size,
            "blocked_apps" to BlockedAppsStore.get(this).size,
            "excluded_apps" to ExcludedAppsStore.get(this).size,
            "adlist_enabled" to AdListStore.isEnabled(this),
            "adlist_entries" to AdListStore.entryCount(this),
            "strict" to StrictStore.isEnabled(this),
            "log_allowed_dns" to LogSettings.logAllowedDns(this),
            "log_urls" to LogSettings.logUrls(this),
            "usage_access" to UsageCollector.hasAccess(this),
            "a11y_enabled" to UiUtil.isUrlFilterEnabled(this),
            // OEM battery managers kill un-exempted background services; this is
            // the first thing to check when the integrity trail shows gaps.
            "battery_opt_exempt" to (pm?.isIgnoringBatteryOptimizations(packageName) ?: false),
        )
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.diag_export)))
    }

    private fun confirmClear() {
        UiUtil.guard(this, GuardPolicy.Action.DISABLE_LOGGING) { showClearConfirm() }
    }

    private fun showClearConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diag_clear_title)
            .setMessage(R.string.diag_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.diag_clear) { _, _ ->
                Thread {
                    EventLog.clearBlocking()
                    // Sequence numbers continue after a clear, and the clear itself is
                    // the first new line, so a wiped log is never mistaken for a quiet one.
                    EventLog.activity(
                        LogEvents.service(System.currentTimeMillis(), "log", "cleared"),
                        urgent = true,
                    )
                    runOnUiThread { refresh() }
                }.start()
            }
            .show()
    }
}
