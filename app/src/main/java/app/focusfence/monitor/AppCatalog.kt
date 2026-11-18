package app.focusfence.monitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import app.focusfence.dns.WatchScope
import java.util.Locale

/** Installed apps for the watch picker, auto-sorted into [WatchScope.Group]s. */
object AppCatalog {

    data class App(val pkg: String, val label: String, val group: WatchScope.Group)

    fun apps(context: Context): List<App> {
        val pm = context.packageManager
        val browsers = browsers(context)
        val skip = alwaysSkip(context)
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName !in skip }
            .map { ai ->
                App(ai.packageName, pm.getApplicationLabel(ai).toString(), WatchScope.group(ai.packageName, ai.category, ai.packageName in browsers))
            }
            .sortedWith(compareBy<App> { it.group.ordinal }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    /** Apps that open web links (needs the VIEW/https <queries> entry on Android 11+). */
    fun browsers(context: Context): Set<String> =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), 0,
        ).map { it.activityInfo.packageName }.toSet()

    /**
     * Never captured, even in "all apps": nothing there is meaningful to an
     * accountability partner, and the keyboard would only capture typing.
     * (Settings is skipped too; the integrity trail already records changes there.)
     */
    fun alwaysSkip(context: Context): Set<String> {
        val pm = context.packageManager
        val skip = HashSet<String>()
        skip += context.packageName
        skip += "com.android.systemui"
        skip += "com.android.settings"
        skip += "android"
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .forEach { skip += it.activityInfo.packageName }
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { skip += it.packageName }
        }
        return skip
    }
}
