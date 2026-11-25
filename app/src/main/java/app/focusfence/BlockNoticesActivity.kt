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

        // Not a loosening action (sites stay blocked either way), so no strict guard.
        b.styleGroup.check(
            when (BlockScreenSettings.style(this)) {
                BlockScreen.Style.NOTIFY -> R.id.styleNotify
                BlockScreen.Style.OFF -> R.id.styleOff
            }
        )
        b.styleGroup.setOnCheckedChangeListener { _, id ->
            BlockScreenSettings.setStyle(
                this,
                when (id) {
                    R.id.styleOff -> BlockScreen.Style.OFF
                    else -> BlockScreen.Style.NOTIFY
                },
            )
        }
        for (i in 0 until b.styleGroup.childCount) {
            b.styleGroup.getChildAt(i).let { Tiles.style(it, Tiles.pos(i, b.styleGroup.childCount), clickable = true) }
        }

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

        b.rowHistory.prefTitle.setText(R.string.history_title)
        b.rowHistory.prefSummary.setText(R.string.history_row_summary)
        b.rowHistory.prefSummary.visibility = View.VISIBLE
        b.rowHistory.root.setOnClickListener { startActivity(android.content.Intent(this, BlockHistoryActivity::class.java)) }
        Tiles.group(b.historyGroup)
    }

    private fun switchRow(r: RowPrefSwitchBinding, title: Int, summary: Int) {
        r.prefTitle.setText(title)
        r.prefSummary.setText(summary)
        r.prefSummary.visibility = View.VISIBLE
        if (r.root.id != b.rowPartner.root.id) r.root.setOnClickListener { r.prefSwitch.toggle() }
    }
}
