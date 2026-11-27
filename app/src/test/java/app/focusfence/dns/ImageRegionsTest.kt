package app.focusfence.dns

import app.focusfence.dns.ImageRegions.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRegionsTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun smallPictureIsCheckedOnItsOwn() {
        val thumb = Box(40, 900, 540, 1400) // a quarter-screen-wide feed picture
        assertEquals(listOf(thumb), ImageRegions.pick(w, h, listOf(thumb)))
    }

    @Test
    fun iconsAndFullScreenPicturesAreSkipped() {
        val avatar = Box(20, 300, 140, 420)
        val fullScreen = Box(0, 0, 1080, 2400)
        // Nothing usable from the tree: the screen is checked as three squares instead, if it looked a bit risky.
        assertEquals(ImageRegions.tiles(w, h), ImageRegions.pick(w, h, listOf(avatar, fullScreen), whole = 0.2))
        assertTrue(ImageRegions.pick(w, h, listOf(avatar), whole = 0.03).isEmpty()) // a plain chat: no extra runs
    }

    @Test
    fun biggestFirstAtMostThreeNoNestedDuplicates() {
        val card = Box(0, 200, 1080, 1100)
        val insideCard = Box(100, 300, 900, 1000)
        val a = Box(0, 1200, 500, 1700)
        val b = Box(540, 1200, 1080, 1700)
        val c = Box(0, 1800, 400, 2200)
        val picked = ImageRegions.pick(w, h, listOf(c, insideCard, a, card, b))
        assertEquals(3, picked.size)
        assertEquals(card, picked[0])
        assertFalse(insideCard in picked)
    }

    @Test
    fun boundsOffScreenAreClipped() {
        val half = Box(-200, 2100, 600, 2700) // scrolled half off the bottom
        assertEquals(listOf(Box(0, 2100, 600, 2400)), ImageRegions.pick(w, h, listOf(half)))
    }

    @Test
    fun tilesCoverTheTallScreen() {
        val t = ImageRegions.tiles(w, h)
        assertEquals(listOf(Box(0, 0, 1080, 1080), Box(0, 660, 1080, 1740), Box(0, 1320, 1080, 2400)), t)
        assertEquals(listOf(Box(0, 0, 1080, 1080), Box(660, 0, 1740, 1080), Box(1320, 0, 2400, 1080)), ImageRegions.tiles(2400, 1080))
        assertTrue(ImageRegions.tiles(1000, 1100).isEmpty()) // nearly square: the whole-screen check is enough
    }

    @Test
    fun pictureClasses() {
        assertTrue(ImageRegions.isPictureClass("android.widget.ImageView"))
        assertTrue(ImageRegions.isPictureClass("android.widget.Image")) // Chrome's web images
        assertTrue(ImageRegions.isPictureClass("android.view.TextureView"))
        assertFalse(ImageRegions.isPictureClass("android.widget.TextView"))
        assertFalse(ImageRegions.isPictureClass(null))
    }

    @Test
    fun bodyResultsMergeByHighestScore() {
        val m = ImageRegions.mergeBody(mapOf("FACE_FEMALE" to 0.8, "BUTTOCKS_EXPOSED" to 0.1), mapOf("BUTTOCKS_EXPOSED" to 0.6))
        assertEquals(mapOf("FACE_FEMALE" to 0.8, "BUTTOCKS_EXPOSED" to 0.6), m)
        assertEquals(null, ImageRegions.mergeBody(null, null))
    }
}
