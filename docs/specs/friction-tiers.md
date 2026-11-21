# Friction tiers (per-action guard levels)

**Status: implemented.** Code: `dns/GuardPolicy.kt` (pure levels + requirement), `data/GuardStore.kt`
(per-action config), `UiUtil.guard(...)` (the gate), configured in `StrictModeActivity`.

## Model

Strict mode is the friction system. A guard **level** sets *how much* friction each loosening
action carries, so everyday actions can stay a simple delay while the ones the owner wants to
commit to can require a partner. Partner gating is opt-in per action, never a default.

| Level | What it takes |
|-------|---------------|
| None | proceed immediately |
| Delay | the timed strict-unlock window (request, wait 10 min, get a notification, act within 15 min) |
| Your PIN | the owner PIN, entered at the moment of the action |
| Partner PIN | a PIN the partner holds, entered at the moment of the action |

A level is the **sole authority** for its action while strict mode is on. The two PIN levels prompt
at action time and do **not** open the global unlock window, so satisfying a weaker action never
grants a stronger one (unlocking to turn the VPN off never also unblocks an app). When strict mode
is off, nothing is gated.

`GuardPolicy.requirement(...)` is pure and tested: it folds in whether strict mode is on, whether
the timed window is open, and whether the needed PIN exists — a PIN level falls back to a timed
unlock when that PIN was never set, so a forgotten PIN never locks the owner out.

## Actions and defaults

`GuardPolicy.Action` covers: turn off the VPN, switch strategy, disable logging, disable capture,
remove a block rule, loosen a bundle, remove an allowlisted site, unblock an app, exclude an app
from the VPN, disable strict mode, remove device protection.

*Exclude an app* is guarded by what the app can reach (`dns/ExclusionPolicy`): games, music and
maps apps toggle instantly (many refuse to run behind a VPN and can't show adult content);
browsers, social, video, photo and uncategorised apps are guarded. Removing an exclusion is never
guarded. **Every action defaults to Delay** — partner gating is off
everywhere until the owner turns it on (e.g. setting *Unblock an app* to Partner PIN). A
`BundlesTest`-style invariant asserts no action ships defaulting to Partner.

## Where it's enforced

Every loosening site calls `UiUtil.guard(context, action) { applyChange() }` instead of a bare
strict-lock check: `BlocklistFragment` (remove rule / unblock app), `HomeFragment` (VPN off,
disable strict), `DiagnosticsActivity` (disable logging, clear logs), `CategoriesActivity` (loosen
bundle, remove allowlist), `MonitoringActivity` (disable capture, widen retention),
`StrictModeActivity` (disable strict). Switch-driven sites revert their control immediately and
re-apply only when the guard is satisfied.

Changing a guard level in `StrictModeActivity` is itself gated: raising friction is always allowed;
lowering it while locked goes through the unlock, so strict mode can't be weakened action-by-action.

## Relationship to the global unlock mode

`PinStore.UnlockMode` (Timer / Owner PIN / Partner PIN) remains the authority for the generic
`showStrictLockedDialog` path and the Delay level's timed window. Per-action PIN levels sit above
it: an action set to Partner PIN always prompts the partner PIN regardless of the global mode.
