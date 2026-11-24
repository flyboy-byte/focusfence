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
 * from what actually happened. Answers never change what a partner is sent; "Send blurred" is the one way a shot
 * reaches a partner, and only as a [app.focusfence.dns.ShotBlur] thumbnail.
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
        v.findViewById<Button>(R.id.reviewShare).setOnClickListener { share(e, file) }
        return v
    }

    /** Phase 2 level B: the owner chooses; only the [app.focusfence.dns.ShotBlur] thumbnail goes, never the image. */
    private fun share(e: ReviewPolicy.Entry, file: File) {
        if (app.focusfence.sync.SyncStore.deviceId(this) == null) {
            android.widget.Toast.makeText(this, R.string.review_share_no_server, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val thumb = app.focusfence.sync.ShareQueue.thumbnail(bmp)
        bmp.recycle()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(ImageView(this).apply {
            setImageBitmap(app.focusfence.sync.ShareQueue.preview(thumb, (160 * resources.displayMetrics.density).toInt()))
            adjustViewBounds = true
            contentDescription = getString(R.string.review_image_desc)
        })
        box.addView(TextView(this).apply { setText(R.string.review_share_body); setPadding(0, pad / 2, 0, 0) })
        val note = android.widget.EditText(this).apply { setHint(R.string.review_share_note); maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
        box.addView(note)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_share_title)
            .setView(box)
            .setPositiveButton(R.string.review_share_send) { _, _ ->
                app.focusfence.sync.ShareQueue.enqueue(this, thumb, e.t, UiUtil.appLabel(this, e.pkg), e.label, e.percent, note.text?.toString().orEmpty())
                android.widget.Toast.makeText(this, R.string.review_share_sent, android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
