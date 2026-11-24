package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotBlurTest {
    private fun solid(w: Int, h: Int, c: Int) = IntArray(w * h) { c }

    @Test
    fun aPhoneScreenshotBecomesAtMost48PixelsOnItsLongEdge() {
        val t = ShotBlur.thumbnail(solid(720, 1600, 0xff112233.toInt()), 720, 1600)
        assertEquals(48, t.h)
        assertEquals(22, t.w)
        assertEquals(t.w * t.h * 3, t.rgb.size)
    }

    @Test
    fun askingForMoreDetailIsIgnored() {
        val t = ShotBlur.thumbnail(solid(1000, 1000, 0), 1000, 1000, maxEdge = 500)
        assertTrue(t.w <= ShotBlur.MAX_EDGE && t.h <= ShotBlur.MAX_EDGE)
    }

    @Test
    fun aSolidColourStaysThatColour() {
        val t = ShotBlur.thumbnail(solid(100, 50, 0xff336699.toInt()), 100, 50)
        assertEquals(0x33, t.rgb[0].toInt() and 0xff)
        assertEquals(0x66, t.rgb[1].toInt() and 0xff)
        assertEquals(0x99, t.rgb[2].toInt() and 0xff)
    }

    @Test
    fun fineDetailIsGone() {
        // A 1-pixel black/white stripe pattern (the kind of edge detail that makes an image recognisable) averages to grey.
        val w = 480; val h = 480
        val stripes = IntArray(w * h) { i -> if ((i % w) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        val t = ShotBlur.thumbnail(stripes, w, h)
        for (b in t.rgb) assertTrue((b.toInt() and 0xff) in 120..135)
    }

    @Test
    fun tinyImagesStillWork() {
        val t = ShotBlur.thumbnail(solid(1, 1, 0xffffffff.toInt()), 1, 1)
        assertEquals(1, t.w); assertEquals(1, t.h)
    }
}
