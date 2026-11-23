package app.focusfence.dns

import app.focusfence.dns.ExclusionPolicy.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

class ExclusionPolicyTest {
    private fun c(pkg: String, cat: Int, browser: Boolean = false) =
        ExclusionPolicy.classify(pkg, cat, browser, WatchScope.group(pkg, cat, browser))

    @Test fun gamesAudioAndMapsAreFree() {
        assertEquals(Risk.FREE, c("com.some.game", 0))
        assertEquals(Risk.FREE, c("com.some.music", 1))
        assertEquals(Risk.FREE, c("com.some.maps", 6))
    }

    @Test fun browsersAreGuardedWhateverTheyDeclare() {
        assertEquals(Risk.GUARDED, c("com.brave.browser", 0, browser = true))
    }

    @Test fun socialVideoAndImageAppsAreGuarded() {
        assertEquals(Risk.GUARDED, c("com.instagram.android", -1))
        assertEquals(Risk.GUARDED, c("com.google.android.youtube", 2))
        assertEquals(Risk.GUARDED, c("com.google.android.apps.photos", 3))
        // A social app that claims to be a game is still social.
        assertEquals(Risk.GUARDED, c("com.snapchat.android", 0))
    }

    @Test fun openGamesAreGuarded() {
        assertEquals(Risk.GUARDED, c("com.roblox.client", 0))
    }

    @Test fun uncategorisedAndOtherCategoriesAreGuarded() {
        assertEquals(Risk.GUARDED, c("com.unknown.app", -1))
        assertEquals(Risk.GUARDED, c("com.some.productivity", 7))
    }

    @Test fun onlyAddedGuardedAppsNeedFriction() {
        val risk = { p: String -> if (p.startsWith("game")) Risk.FREE else Risk.GUARDED }
        assertEquals(setOf("web"), ExclusionPolicy.needsGuard(setOf("old"), setOf("old", "game1", "web"), risk))
        // Removing an exclusion is tightening.
        assertEquals(emptySet<String>(), ExclusionPolicy.needsGuard(setOf("web"), emptySet(), risk))
        // Already excluded stays excluded without a new prompt.
        assertEquals(emptySet<String>(), ExclusionPolicy.needsGuard(setOf("web"), setOf("web"), risk))
    }
}
