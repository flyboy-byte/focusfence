package app.focusfence.dns

/**
 * Difference hash ("dHash") of a screen, for skipping the AI when nothing
 * changed. The caller shrinks the screenshot to 9×8 and passes luminance; each
 * of the 64 bits says whether a pixel is brighter than its right neighbour.
 * Near-identical screens (same page, a blinking cursor, a clock tick) land
 * within a few bits; a new page flips dozens.
 *
 * Why: the 2026-11-19 export scored 136 screenshots, mostly of screens that
 * hadn't changed. Hashing costs ~0.1 ms against ~90 ms for the model.
 */
object ImageHash {

    const val W = 9
    const val H = 8

    /** [luma] is [W]×[H] row-major brightness values (any scale). */
    fun dHash(luma: IntArray): Long {
        require(luma.size == W * H)
        var h = 0L
        var bit = 0
        for (y in 0 until H) {
            for (x in 0 until W - 1) {
                if (luma[y * W + x] > luma[y * W + x + 1]) h = h or (1L shl bit)
                bit++
            }
        }
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Rec. 601 luma from packed ARGB. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Same screen as last time? Only within the same app and a bounded age, so
     * a static screen is still re-scored now and then.
     */
    fun unchanged(
        prevHash: Long?, prevPkg: String?, prevAt: Long,
        hash: Long, pkg: String, now: Long,
        maxBits: Int = 5, maxAgeMs: Long = 10 * 60_000L,
    ): Boolean = prevHash != null && prevPkg == pkg && now - prevAt <= maxAgeMs && distance(prevHash, hash) <= maxBits
}
