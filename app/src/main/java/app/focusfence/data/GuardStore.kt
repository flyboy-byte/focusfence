package app.focusfence.data

import android.content.Context
import app.focusfence.dns.GuardPolicy
import app.focusfence.dns.StrictLock

/**
 * Per-action friction levels (see [GuardPolicy]). Stored in the `focusfence`
 * prefs file. A level defaults to the action's [GuardPolicy.Action.default]
 * (partner gating off everywhere) until the owner changes it.
 */
object GuardStore {

    fun level(context: Context, action: GuardPolicy.Action): GuardPolicy.Level {
        val stored = BlocklistStore.prefs(context).getString(action.key, null) ?: return action.default
        return runCatching { GuardPolicy.Level.valueOf(stored) }.getOrDefault(action.default)
    }

    fun setLevel(context: Context, action: GuardPolicy.Action, level: GuardPolicy.Level) {
        BlocklistStore.prefs(context).edit().putString(action.key, level.name).apply()
    }

    /** The live requirement for [action], folding in strict state and which PINs exist. */
    fun requirement(context: Context, action: GuardPolicy.Action): GuardPolicy.Requirement =
        GuardPolicy.requirement(
            strictEnabled = StrictStore.isEnabled(context),
            level = level(context, action),
            timedWindowOpen = StrictStore.state(context) is StrictLock.State.Unlocked,
            ownerPinSet = PinStore.hasOwnerPin(context),
            partnerPinSet = PinStore.hasPartnerPin(context),
        )
}
