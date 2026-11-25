package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun apk(tag: String, name: String = "focus-fence-accountable-$tag.apk") =
        UpdateCheck.Asset(name, UpdateCheck.DOWNLOAD_PREFIX + "$tag/$name", 9_000_000)

    private fun release(tag: String, vararg assets: UpdateCheck.Asset, draft: Boolean = false, pre: Boolean = false) =
        UpdateCheck.Release(tag, draft, pre, assets.toList(), "https://github.com/x/releases/tag/$tag")

    @Test
    fun offersANewerFullRelease() {
        val o = UpdateCheck.offer(release("v2.14.0-accountable", apk("v2.14.0-accountable")), "2.13.0-accountable")!!
        assertEquals("2.14.0-accountable", o.version)
        assertTrue(o.apkUrl.endsWith("/v2.14.0-accountable/focus-fence-accountable-v2.14.0-accountable.apk"))
    }

    @Test
    fun ignoresTheSameOrAnOlderVersion() {
        assertNull(UpdateCheck.offer(release("v2.13.0-accountable", apk("v2.13.0-accountable")), "2.13.0-accountable"))
        assertNull(UpdateCheck.offer(release("v2.12.0-accountable", apk("v2.12.0-accountable")), "2.13.0-accountable"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(UpdateCheck.offer(release("v2.10.0-accountable", apk("v2.10.0-accountable")), "2.9.2-accountable") != null)
    }

    @Test
    fun ignoresTheServersOwnReleases() {
        val tag = "server-v3.0.0"
        assertNull(UpdateCheck.offer(release(tag, apk(tag)), "2.13.0-accountable"))
        assertFalse(UpdateCheck.isAppTag(tag))
        assertTrue(UpdateCheck.isAppTag("v2.16.0-accountable"))
    }

    @Test
    fun ignoresDraftsAndPrereleases() {
        val tag = "v2.14.0-rc1-accountable"
        assertNull(UpdateCheck.offer(release(tag, apk(tag), pre = true), "2.13.0-accountable"))
        assertNull(UpdateCheck.offer(release(tag, apk(tag), draft = true), "2.13.0-accountable"))
    }

    @Test
    fun ignoresAnUnparseableTag() {
        assertNull(UpdateCheck.offer(release("nightly", apk("nightly")), "2.13.0-accountable"))
    }

    @Test
    fun onlyTakesAnApkFromThisRepoAndThisTag() {
        val tag = "v2.14.0-accountable"
        val elsewhere = UpdateCheck.Asset("focus-fence-accountable.apk", "https://example.com/$tag/focus-fence-accountable.apk", 1)
        val otherTag = apk("v2.11.0-accountable")
        val mapping = UpdateCheck.Asset("mapping.txt", UpdateCheck.DOWNLOAD_PREFIX + "$tag/mapping.txt", 1)
        assertNull(UpdateCheck.offer(release(tag, elsewhere, otherTag, mapping), "2.13.0-accountable"))
        assertNull(UpdateCheck.offer(release(tag), "2.13.0-accountable"))
    }

    @Test
    fun checksEveryThreeHours() {
        val gap = UpdateCheck.CHECK_EVERY_MS
        assertEquals(3 * 3_600_000L, gap)
        assertTrue(UpdateCheck.isDue(0, 1_000))
        assertFalse(UpdateCheck.isDue(10 * gap, 10 * gap + gap - 1))
        assertTrue(UpdateCheck.isDue(10 * gap, 11 * gap))
        assertTrue("clock went backwards", UpdateCheck.isDue(10 * gap, 9 * gap))
    }

    @Test
    fun signerMustMatchExactly() {
        assertTrue(UpdateCheck.sameSigner(listOf("AB12"), listOf("ab12")))
        assertFalse(UpdateCheck.sameSigner(listOf("ab12"), listOf("cd34")))
        assertFalse(UpdateCheck.sameSigner(listOf("ab12"), listOf("ab12", "cd34")))
        assertFalse(UpdateCheck.sameSigner(emptyList(), emptyList()))
    }

    private val body = """
        **Focus Fence AC** — a site blocker.

        ## What's new

        ### Added
        - check GitHub for updates and install with one tap (`abb69ad`, [#49](https://github.com/x/pull/49))
        - **ui:** five-tab layout (`933523f`, [#29](https://github.com/x/pull/29))
        - ui: Settings as an ordered list (`8ff7797`)
        ### Docs
        - README in plain words (`bbcb835`, [#47](https://github.com/x/pull/47))
        ### Fixed
        - network blips are one count (`760d05e`)

        _Undo any line with `git revert <commit>`._

        ## Install
        - Signing key: stable
    """.trimIndent()

    @Test
    fun notesArePlainForThePhone() {
        assertEquals(
            "Added\n• Check GitHub for updates and install with one tap\n• Five-tab layout\n• Settings as an ordered list\nFixed\n• Network blips are one count",
            UpdateCheck.userNotes(body),
        )
    }

    @Test
    fun serverAndToolingLinesStayOffThePhone() {
        val b = "## What's new\n\n### Changed\n" +
            "- Ui: follow the system colours (`3eb8bc8`, [#67](https://x/67))\n" +
            "- server: systemd install with automatic rollback (`76efb4a`, [#66](https://x/66))\n" +
            "- **ci:** release notes (`c6bf699`)\n"
        assertEquals("Changed\n• Follow the system colours", UpdateCheck.userNotes(b))
        assertEquals("2.16.0", UpdateCheck.shortVersion("2.16.0-accountable"))
    }

    @Test
    fun aBodyWithoutWhatsNewHasNoNotes() {
        assertEquals("", UpdateCheck.userNotes("**Focus Fence AC** — accountability build.\n\n- Signing key: stable"))
    }

    @Test
    fun theOfferCarriesTheNotes() {
        val r = UpdateCheck.Release("v2.15.0-accountable", false, false, listOf(apk("v2.15.0-accountable")), "", body)
        assertTrue(UpdateCheck.offer(r, "2.14.0-accountable")!!.notes.startsWith("Added"))
    }
}
