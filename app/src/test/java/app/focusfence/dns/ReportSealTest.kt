package app.focusfence.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportSealTest {
    private val key = ReportSeal.newKey()

    @Test fun keyIs43UrlSafeChars() {
        assertEquals(43, key.length)
        assertEquals(32, ReportSeal.keyBytes(key)!!.size)
        assertNull(ReportSeal.keyBytes("too-short"))
    }

    @Test fun roundTrips() {
        val blob = ReportSeal.seal(key, "hello".toByteArray(), "dev1")
        assertArrayEquals("hello".toByteArray(), ReportSeal.open(key, blob, "dev1"))
    }

    @Test fun wrongKeyOtherDeviceOrTamperingAllFail() {
        val blob = ReportSeal.seal(key, "hello".toByteArray(), "dev1")
        assertNull(ReportSeal.open(ReportSeal.newKey(), blob, "dev1"))
        assertNull(ReportSeal.open(key, blob, "dev2"))
        val flipped = blob.toCharArray().also { it[20] = if (it[20] == 'A') 'B' else 'A' }.concatToString()
        assertNull(ReportSeal.open(key, flipped, "dev1"))
        assertNull(ReportSeal.open(key, "", "dev1"))
    }

    @Test fun freshIvEveryTime() =
        assertNotEquals(ReportSeal.seal(key, "x".toByteArray(), "d"), ReportSeal.seal(key, "x".toByteArray(), "d"))
}
