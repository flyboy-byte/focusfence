package app.focusfence

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.BlocklistStore
import app.focusfence.databinding.ActivityBlockedBinding
import app.focusfence.dns.BlockScreen
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The "Blocked by Focus Fence" screen. Opened over the browser by the
 * accessibility service when the page in front is one the filter just blocked
 * (see [BlockScreen] for why this is an app screen and not a web page), or from
 * the backup notification when that service is off.
 *
 * Runs in its own task and stays out of Recents, so closing it never drops the
 * user into the rest of Focus Fence or leaves a stray entry behind.
 */
class BlockedActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DOMAIN = "domain"

        fun intent(context: Context, domain: String): Intent =
            Intent(context, BlockedActivity::class.java)
                .putExtra(EXTRA_DOMAIN, domain)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
    }

    private lateinit var binding: ActivityBlockedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.blockedHomeButton.setOnClickListener { goHome() }
        binding.blockedOpenAppButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        // Back behaves like the Home button, for the same reason (see goHome).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
        binding.blockedDomain.text = domain
        binding.blockedReason.text = reasonText(domain)
    }

    /** Looked up fresh rather than passed in, so the screen always matches the rule as it stands. */
    private fun reasonText(domain: String): String {
        val rule = BlocklistStore.rulesByDomain(this)[domain]
            ?: return getString(R.string.blocked_reason_always)
        return when (BlockScreen.why(rule)) {
            BlockScreen.Why.ALWAYS -> getString(R.string.blocked_reason_always)
            BlockScreen.Why.SCHEDULE -> getString(
                R.string.blocked_reason_schedule,
                timeOfDay(rule.startMinute), timeOfDay(rule.endMinute),
            )
            BlockScreen.Why.BUDGET -> getString(R.string.blocked_reason_budget, rule.budgetMinutes)
            BlockScreen.Why.COMMITMENT -> getString(
                R.string.blocked_reason_commitment,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(rule.commitUntilEpochMillis)),
            )
            BlockScreen.Why.SECTION -> getString(R.string.blocked_reason_section)
        }
    }

    private fun timeOfDay(minuteOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
        return android.text.format.DateFormat.getTimeFormat(this).format(cal.time)
    }

    /**
     * Home rather than back: back would land on the browser's error page for
     * the very site that was just blocked.
     */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
