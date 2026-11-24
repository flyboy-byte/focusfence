package app.focusfence

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import app.focusfence.data.BlocklistStore
import app.focusfence.databinding.ActivityOnboardingBinding

/** First-launch wizard walking through how the app works and its limits. */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val KEY_ONBOARDED = "onboarded"
    }

    private lateinit var binding: ActivityOnboardingBinding
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        index = savedInstanceState?.getInt("page") ?: 0

        binding.skipButton.setOnClickListener { done() }
        binding.backButton.setOnClickListener {
            if (index > 0) {
                index--
                render()
            }
        }
        binding.nextButton.setOnClickListener {
            if (index >= HelpContent.PAGES.size - 1) {
                done()
            } else {
                index++
                render()
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", index)
    }

    private fun render() {
        val page = HelpContent.PAGES[index]
        binding.pageTitle.setText(page.titleRes)
        binding.pageBody.text =
            HtmlCompat.fromHtml(getString(page.bodyRes), HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.pageIndicator.text =
            getString(R.string.onboarding_progress, index + 1, HelpContent.PAGES.size)
        binding.backButton.visibility =
            if (index == 0) android.view.View.INVISIBLE else android.view.View.VISIBLE
        binding.nextButton.setText(
            if (index >= HelpContent.PAGES.size - 1) R.string.onboarding_done else R.string.onboarding_next
        )
        binding.bodyScroll.scrollTo(0, 0)
    }

    private fun done() {
        BlocklistStore.prefs(this).edit().putBoolean(KEY_ONBOARDED, true).apply()
        // Straight into the guide's checklist, so the first run ends with what to turn on, not an empty home screen.
        startActivity(android.content.Intent(this, SetupActivity::class.java))
        finish()
    }
}
