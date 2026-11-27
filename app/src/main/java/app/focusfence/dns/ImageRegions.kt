package app.focusfence.dns

/**
 * Plan 17 piece 5: which parts of a screenshot to check on their own.
 *
 * The models see the whole screen shrunk to 224 px (MobileNet) or 320 px (NudeNet), so a picture a quarter of the
 * screen wide ends up a few dozen pixels across and its body parts a few pixels: it was never caught. Cutting the
 * picture out first gives the models the same detail a full-screen picture gets.
 *
 * Where the pictures are comes from the accessibility tree (image and video views, with their bounds). When it shows
 * none (custom-drawn feeds, games), the screen is checked as [MAX] overlapping squares down its long side instead, but
 * only if the whole screen already scored at least [TILES_FROM]: a chat with no pictures isn't worth three more runs.
 */
object ImageRegions {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height
        fun intersect(o: Box): Long {
            val w = minOf(right, o.right) - maxOf(left, o.left)
            val h = minOf(bottom, o.bottom) - maxOf(top, o.top)
            return if (w > 0 && h > 0) w.toLong() * h else 0
        }
    }

    /** At most this many extra checks per screenshot: each is one MobileNet run (~20-40 ms), NudeNet only if risky. */
    const val MAX = 3

    /** Smaller than this on a side is an icon or avatar, not a picture worth a model run. */
    const val MIN_SIDE = 160

    /** A picture covering more than this of the screen is already seen well by the whole-screen check. */
    const val MAX_SHARE = 0.6

    /** Whole-screen risk score from which a screen with no picture views is checked as squares. 92% of real shots score below it. */
    const val TILES_FROM = 0.10

    /** Class names (or their tails) of views that show pictures or video. */
    private val PICTURE_CLASSES = listOf("Image", "Video", "TextureView", "SurfaceView")

    fun isPictureClass(className: CharSequence?): Boolean =
        className != null && PICTURE_CLASSES.any { className.contains(it) }

    /** The regions to check for a [w] x [h] screenshot, given the picture views' [found] bounds and the [whole] score. */
    fun pick(w: Int, h: Int, found: List<Box>, whole: Double = 1.0): List<Box> {
        val screen = w.toLong() * h
        if (screen <= 0) return emptyList()
        val usable = found
            .map { Box(it.left.coerceIn(0, w), it.top.coerceIn(0, h), it.right.coerceIn(0, w), it.bottom.coerceIn(0, h)) }
            .filter { it.width >= MIN_SIDE && it.height >= MIN_SIDE && it.area <= screen * MAX_SHARE }
            .sortedByDescending { it.area }
        val kept = ArrayList<Box>()
        for (b in usable) {
            // A thumbnail inside a card that's already kept (or the same view twice) adds nothing.
            if (kept.any { it.intersect(b) >= b.area * 0.7 }) continue
            kept += b
            if (kept.size == MAX) break
        }
        return when {
            kept.isNotEmpty() -> kept
            whole >= TILES_FROM -> tiles(w, h)
            else -> emptyList()
        }
    }

    /** [MAX] squares along the long side (top, middle, bottom on a phone held upright); none for a square screen. */
    fun tiles(w: Int, h: Int): List<Box> {
        val side = minOf(w, h)
        val long = maxOf(w, h)
        if (side < MIN_SIDE || long < side * 1.3) return emptyList()
        val starts = listOf(0, (long - side) / 2, long - side).distinct()
        return starts.map { s -> if (h >= w) Box(0, s, w, s + side) else Box(s, 0, s + side, h) }
    }

    /** Body-part results from several crops: the highest score per part. */
    fun mergeBody(a: Map<String, Double>?, b: Map<String, Double>?): Map<String, Double>? {
        if (a == null) return b
        if (b == null) return a
        return (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0.0, b[it] ?: 0.0) }
    }
}
