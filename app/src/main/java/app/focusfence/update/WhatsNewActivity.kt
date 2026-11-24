package app.focusfence.update

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.R
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

/**
 * Settings → About → What's new: the notes of the last few releases, fetched from GitHub when opened (the same
 * plain text the update banner shows). Nothing is fetched unless the owner opens this screen.
 */
class WhatsNewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val toolbar = MaterialToolbar(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, pad)
        }
        val status = TextView(this).apply { setText(R.string.whats_new_loading) }
        list.addView(status)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.whats_new_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val installed = Updates.installedVersion(this)
        thread(name = "whats-new") {
            val releases = runCatching { Updates.recentReleases() }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (releases == null) {
                    status.setText(R.string.whats_new_failed)
                    return@runOnUiThread
                }
                list.removeView(status)
                for ((version, notes) in releases) {
                    list.addView(TextView(this).apply {
                        text = if (version == installed) "$version ${getString(R.string.whats_new_installed)}" else version
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                        setPadding(0, (16 * d).toInt(), 0, (4 * d).toInt())
                    })
                    list.addView(TextView(this).apply {
                        text = notes.ifEmpty { getString(R.string.update_no_notes).substringBefore('.') + "." }
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        setLineSpacing(2 * d, 1f)
                    })
                }
            }
        }
    }
}
