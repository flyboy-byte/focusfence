package app.focusfence.server.compat

import app.focusfence.dns.Protocol
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D-041: every protocol level still in the window has its compatibility suite. Bumping Protocol.LEVEL without adding
 * `CompatLevel<N>Test` for the new level, or deleting an old one too early, fails CI here.
 */
class CompatWindowTest {
    @Test
    fun everyLevelInTheWindowHasASuite() {
        for (level in Protocol.OLDEST..Protocol.LEVEL) {
            val found = runCatching { Class.forName("app.focusfence.server.compat.CompatLevel${level}Test") }.isSuccess
            assertTrue(found, "protocol level $level is in the window (${Protocol.OLDEST}..${Protocol.LEVEL}) but has no CompatLevel${level}Test")
        }
    }
}
