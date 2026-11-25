package app.focusfence.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Seven vertical bars (one per day) with counts above and day labels below.
 * Drawn directly: a tiny fixed chart doesn't justify a charting library.
 * Today's bar uses the primary colour; other days a softer container colour.
 */
class WeekChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var labels: List<String> = emptyList()
    private var values: List<Int> = emptyList()
    private var format: (Int) -> String = { it.toString() }
    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * resources.displayMetrics.scaledDensity
    }
    private val rect = RectF()

    /** [format] turns a bar's value into its label (counts by default; the stats screen passes minutes as "2 h"). */
    fun setData(labels: List<String>, values: List<Int>, format: (Int) -> String = { it.toString() }) {
        this.labels = labels
        this.values = values
        this.format = format
        contentDescription = labels.zip(values).joinToString { "${it.first} ${format(it.second)}" }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (160 * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val soft = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
        val textColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textPaint.color = textColor
        val n = values.size
        val labelH = 20 * density
        val valueH = 18 * density
        val top = valueH
        val bottom = height - labelH
        val slot = width.toFloat() / n
        val barW = slot * 0.56f
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val radius = 6 * density
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val frac = values[i].toFloat() / max
            // A visible stub even for zero, so the axis reads as seven days.
            val barTop = bottom - ((bottom - top) * frac).coerceAtLeast(3 * density)
            barPaint.color = if (i == n - 1) primary else soft
            rect.set(cx - barW / 2, barTop, cx + barW / 2, bottom)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            canvas.drawText(format(values[i]), cx, barTop - 5 * density, textPaint)
            canvas.drawText(labels.getOrElse(i) { "" }, cx, height - 4 * density, textPaint)
        }
    }
}
