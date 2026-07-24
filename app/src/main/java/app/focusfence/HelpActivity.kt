package app.focusfence

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import app.focusfence.databinding.ActivityHelpBinding

/** The full guide, reachable anytime from the toolbar's ? action. */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val html = buildString {
            for (page in HelpContent.PAGES) {
                append("<b>").append(getString(page.titleRes)).append("</b><br><br>")
                append(getString(page.bodyRes))
                append("<br><br><br>")
            }
        }
        binding.helpText.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
