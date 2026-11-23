package app.focusfence

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.focusfence.data.EventLog
import app.focusfence.data.MonitorSettings
import app.focusfence.data.ReviewStore
import app.focusfence.databinding.ActivityReviewBinding
import app.focusfence.dns.LogEvents
import app.focusfence.dns.ReviewPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The owner's own look at screenshots the AI scored above their chosen
 * percentage. Local-only (see [ReviewPolicy]); answering "not a problem" or
 * "real" deletes the image and logs a `rev` record so detection can be tuned
 * from what actually happened. It never changes what a partner is sent.
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.reviewSwitch.setOnCheckedChangeListener { _, on ->
            MonitorSettings.setReviewEnabled(this, on)
            bind()
        }
        binding.reviewPercentButton.setOnClickListener {
            val choices = ReviewPolicy.PERCENT_CHOICES
            val labels = choices.map { "$it%" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.review_percent_title)
                .setSingleChoiceItems(labels, choices.indexOf(MonitorSettings.reviewPercent(this)).coerceAtLeast(0)) { d, i ->
                    MonitorSettings.setReviewPercent(this, choices[i])
                    d.dismiss()
                    bind()
                }
                .show()
        }
        binding.reviewClearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.review_clear_confirm)
                .setPositiveButton(R.string.review_clear) { _, _ -> ReviewStore.clear(this); bind() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val on = MonitorSettings.reviewEnabled(this)
        binding.reviewSwitch.setOnCheckedChangeListener(null)
        binding.reviewSwitch.isChecked = on
        binding.reviewSwitch.setOnCheckedChangeListener { _, v -> MonitorSettings.setReviewEnabled(this, v); bind() }
        binding.reviewPercentButton.text = getString(R.string.review_percent_button, MonitorSettings.reviewPercent(this))
        binding.reviewPercentButton.visibility = if (on) View.VISIBLE else View.GONE

        val items = ReviewStore.list(this)
        binding.reviewList.removeAllViews()
        binding.reviewEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.reviewClearButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for ((entry, file) in items) binding.reviewList.addView(row(inflater, entry, file))
    }

    private fun row(inflater: LayoutInflater, e: ReviewPolicy.Entry, file: File): View {
        val v = inflater.inflate(R.layout.item_review, binding.reviewList, false)
        val image = v.findViewById<ImageView>(R.id.reviewImage)
        // Small file (<= 720 px edge): decoding on the UI thread is fine.
        image.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        v.findViewById<TextView>(R.id.reviewMeta).text = getString(
            R.string.review_meta,
            UiUtil.appLabel(this, e.pkg), e.label, e.percent,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.t)),
        )
        v.findViewById<TextView>(R.id.reviewScores).apply {
            text = ReviewPolicy.breakdown(e.scores)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<Button>(R.id.reviewWrong).setOnClickListener { answer(e, file, "wrong") }
        v.findViewById<Button>(R.id.reviewReal).setOnClickListener { answer(e, file, "ok") }
        return v
    }

    private fun answer(e: ReviewPolicy.Entry, file: File, verdict: String) {
        EventLog.activity(LogEvents.review(System.currentTimeMillis(), e.t, e.pkg, e.label, e.percent, verdict))
        ReviewStore.delete(this, file)
        bind()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
