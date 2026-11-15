package app.focusfence.dns

import app.focusfence.dns.GuardPolicy.Level
import app.focusfence.dns.GuardPolicy.Requirement
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardPolicyTest {

    @Test fun strictOffAllowsEverything() {
        for (lvl in Level.values()) {
            assertEquals(Requirement.Allowed, GuardPolicy.requirement(false, lvl, timedWindowOpen = false))
        }
    }

    @Test fun noneLevelAlwaysAllowed() {
        assertEquals(Requirement.Allowed, GuardPolicy.requirement(true, Level.NONE, false))
    }

    @Test fun delayUsesTimedWindow() {
        assertEquals(Requirement.TimedUnlock, GuardPolicy.requirement(true, Level.DELAY, timedWindowOpen = false))
        assertEquals(Requirement.Allowed, GuardPolicy.requirement(true, Level.DELAY, timedWindowOpen = true))
    }

    @Test fun pinLevelsPromptRegardlessOfWindow() {
        // A PIN action is not satisfied by an open timed window — no leak from a weaker unlock.
        assertEquals(Requirement.Pin(false), GuardPolicy.requirement(true, Level.OWN_PIN, timedWindowOpen = true))
        assertEquals(Requirement.Pin(true), GuardPolicy.requirement(true, Level.PARTNER_PIN, timedWindowOpen = true))
    }

    @Test fun pinFallsBackToTimedWhenPinNotSet() {
        assertEquals(Requirement.TimedUnlock,
            GuardPolicy.requirement(true, Level.OWN_PIN, false, ownerPinSet = false))
        assertEquals(Requirement.TimedUnlock,
            GuardPolicy.requirement(true, Level.PARTNER_PIN, false, partnerPinSet = false))
    }

    @Test fun actionsHaveUniqueKeysAndPartnerIsOptIn() {
        val keys = GuardPolicy.Action.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // No action requires a partner by default.
        assertEquals(emptyList<GuardPolicy.Action>(),
            GuardPolicy.Action.values().filter { it.default == Level.PARTNER_PIN })
    }
}
