package app.focusfence.dns

/**
 * The only form in which a screenshot ever leaves the phone (Phase 2, level B; decided 2026-11-23: a partner never
 * receives a clear image of explicit content, whoever chooses to send it). The picture is area-averaged down to at
 * most [MAX_EDGE] pixels on its long edge and then box-blurred, so what travels is a few hundred colour patches:
 * enough to show "a photo app, mostly skin tones" or "a chat screen", never enough to recognise a body or a face.
 * Sent as raw RGB, not JPEG, so the partner's browser paints pixels onto a canvas and needs no image decoder (and the
 * report page's CSP stays without img-src). Pure, so the size ceiling is unit-tested.
 */
object ShotBlur {
    const val MAX_EDGE = 24

    class Thumb(val w: Int, val h: Int, val rgb: ByteArray)

    /** [argb] is [w]×[h] packed ARGB (Android's Bitmap.getPixels layout). */
    fun thumbnail(argb: IntArray, w: Int, h: Int, maxEdge: Int = MAX_EDGE): Thumb {
        require(w > 0 && h > 0 && argb.size >= w * h)
        val edge = minOf(maxEdge.coerceIn(1, MAX_EDGE), maxOf(w, h)) // never above the ceiling, whatever the caller asks; never upscaled
        val scale = maxOf(w, h).toDouble() / edge
        val tw = maxOf(1, Math.round(w / scale).toInt()).coerceAtMost(edge)
        val th = maxOf(1, Math.round(h / scale).toInt()).coerceAtMost(edge)
        // Area average: every source pixel lands in exactly one output cell.
        val sum = Array(3) { LongArray(tw * th) }
        val n = LongArray(tw * th)
        for (y in 0 until h) {
            val ty = (y.toLong() * th / h).toInt()
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val i = ty * tw + (x.toLong() * tw / w).toInt()
                sum[0][i] = sum[0][i] + ((c shr 16) and 0xff); sum[1][i] = sum[1][i] + ((c shr 8) and 0xff)
                sum[2][i] = sum[2][i] + (c and 0xff); n[i]++
            }
        }
        var px = Array(3) { ch -> IntArray(tw * th) { i -> if (n[i] == 0L) 0 else (sum[ch][i] / n[i]).toInt() } }
        repeat(2) { px = Array(3) { ch -> boxBlur(px[ch], tw, th) } }
        val out = ByteArray(tw * th * 3)
        for (i in 0 until tw * th) for (ch in 0 until 3) out[i * 3 + ch] = px[ch][i].toByte()
        return Thumb(tw, th, out)
    }

    /** 3×3 mean, edges clamped. */
    private fun boxBlur(p: IntArray, w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        var s = 0; var c = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) { s += p[yy * w + xx]; c++ }
        }
        s / c
    }
}
