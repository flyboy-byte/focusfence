package app.focusfence

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.BlockScreenSettings
import app.focusfence.data.StrictStore
import app.focusfence.databinding.ActivityBlockNoticesBinding
import app.focusfence.databinding.RowPrefSwitchBinding
import app.focusfence.dns.BlockScreen
import app.focusfence.dns.GuardPolicy
import app.focusfence.ui.Tiles

/** Settings › When a site is blocked (moved out of the Settings list, 2026-11-25). Logic as before, D-024. */
class BlockNoticesActivity : AppCompatActivity() {
    private lateinit var b: ActivityBlockNoticesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBlockNoticesBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setTitle(R.string.notices_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        // Blocked sites always fail silently (DNS answers 0.0.0.0); this only says whether a notification comes too.
        // Not a loosening action (sites stay blocked either way), so no strict guard.
        switchRow(b.rowNotify, R.string.notices_notify_title, R.string.notices_notify_summary)
        b.rowNotify.prefSwitch.isChecked = BlockScreenSettings.style(this) == BlockScreen.Style.NOTIFY
        b.rowNotify.prefSwitch.setOnCheckedChangeListener { _, on ->
            BlockScreenSettings.setStyle(this, if (on) BlockScreen.Style.NOTIFY else BlockScreen.Style.OFF)
        }
        Tiles.group(b.notifyGroup)
        b.historyButton.setOnClickListener { startActivity(android.content.Intent(this, BlockHistoryActivity::class.java)) }

        switchRow(b.rowHighRisk, R.string.notices_high_risk_title, R.string.block_notice_high_risk_summary)
        b.rowHighRisk.prefSwitch.isChecked = BlockScreenSettings.noticeHighRisk(this)
        b.rowHighRisk.prefSwitch.setOnCheckedChangeListener { _, on -> BlockScreenSettings.setNoticeHighRisk(this, on) }

        // Turning partner alerts off is a loosening: behind the friction guard, like removing a partner.
        switchRow(b.rowPartner, R.string.block_partner_repeated, R.string.notices_partner_summary)
        val partner = b.rowPartner.prefSwitch
        partner.isChecked = BlockScreenSettings.partnerRepeated(this)
        partner.setOnClickListener {
            if (partner.isChecked) {
                BlockScreenSettings.setPartnerRepeated(this, true)
                StrictStore.noteComponent(this, "block", "partner_alerts_on")
            } else {
                partner.isChecked = true // revert until the guard is satisfied
                UiUtil.guard(this, GuardPolicy.Action.QUIET_BLOCK_ALERTS) {
                    BlockScreenSettings.setPartnerRepeated(this, false)
                    // A partner relies on this alert, so switching it off is itself something they hear about.
                    StrictStore.noteComponent(this, "block", "partner_alerts_off")
                    partner.isChecked = false
                }
            }
        }
        b.rowPartner.root.setOnClickListener { partner.performClick() }
        Tiles.group(b.riskGroup)

    }

    private fun switchRow(r: RowPrefSwitchBinding, title: Int, summary: Int) {
        r.prefTitle.setText(title)
        r.prefSummary.setText(summary)
        r.prefSummary.visibility = View.VISIBLE
        if (r.root.id != b.rowPartner.root.id) r.root.setOnClickListener { r.prefSwitch.toggle() }
    }
}
