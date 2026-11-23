package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHashTest {

    private fun gradient(seed: Int) = IntArray(ImageHash.W * ImageHash.H) { i -> ((i * 37 + seed * 101) % 256) }

    @Test
    fun identicalAndNearIdenticalScreensMatch() {
        val a = gradient(1)
        val b = a.copyOf().also { it[10] += 1; it[40] -= 1 } // tiny change, e.g. a cursor
        val ha = ImageHash.dHash(a)
        assertEquals(0, ImageHash.distance(ha, ImageHash.dHash(a)))
        assertTrue(ImageHash.distance(ha, ImageHash.dHash(b)) <= 2)
    }

    @Test
    fun differentScreensDiffer() {
        val ha = ImageHash.dHash(gradient(1))
        val hb = ImageHash.dHash(IntArray(72) { i -> if ((i / 9) % 2 == 0) 255 - i else i })
        assertTrue(ImageHash.distance(ha, hb) > 10)
    }

    @Test
    fun flatScreenHashesToZero() {
        assertEquals(0L, ImageHash.dHash(IntArray(72) { 128 }))
    }

    @Test
    fun lumaWeights() {
        assertEquals(255, ImageHash.luma(0xFFFFFFFF.toInt()))
        assertEquals(0, ImageHash.luma(0xFF000000.toInt()))
        assertEquals(149, ImageHash.luma(0xFF00FF00.toInt()))
    }

    @Test
    fun unchangedRules() {
        val h = 0x0F0FL
        assertTrue(ImageHash.unchanged(h, "a", 0, h, "a", 1000))
        assertFalse("different app", ImageHash.unchanged(h, "a", 0, h, "b", 1000))
        assertFalse("too old", ImageHash.unchanged(h, "a", 0, h, "a", 11 * 60_000L))
        assertFalse("no previous", ImageHash.unchanged(null, null, 0, h, "a", 1000))
        assertFalse("changed", ImageHash.unchanged(h, "a", 0, h xor 0xFFFFL, "a", 1000))
    }
}
