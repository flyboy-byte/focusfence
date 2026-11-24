package app.focusfence

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.ui.StatsFragment
import com.google.android.material.appbar.MaterialToolbar

/** Stats on their own screen, opened from Home's menu in the five-tab layout (the classic layout keeps a Stats tab). */
class StatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = MaterialToolbar(this)
        val content = FrameLayout(this).apply { id = R.id.statsContainer }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.stats_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.statsContainer, StatsFragment()).commit()
    }
}
