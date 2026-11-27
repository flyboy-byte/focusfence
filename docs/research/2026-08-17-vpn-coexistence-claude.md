# Q8 — Android VPN coexistence: still one slot, full stop

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items")
**Date:** 2026-08-17
**Feeds:** `docs/archive/approach.md` B4, D-013 — confirms a constraint the whole design already assumes
**Sourcing method:** the current `android.net.VpnService` class reference on
developer.android.com, fetched 2026-08-17. One adjacent claim is labeled as platform knowledge
rather than quoted doc text.

---

## Exact prompt used

The Q8 prompt from `docs/research/prompts.md` (has anything changed in Android 14/15/16 —
a multi-VPN API, third-party per-app routing, or a documented exception), canonical copy there.

---

## Answer: no, still one slot. The expected answer, now with the citation.

The current `VpnService` reference states, verbatim:

> "There can be only one VPN connection running at the same time. The existing interface is
> deactivated when a new one is created."

Source: https://developer.android.com/reference/android/net/VpnService (fetched 2026-08-17 —
the live page documents the API through the current platform release).

On the specific "has anything changed" probes:

- **No multi-VPN API** appears anywhere in the class reference. The `Builder`'s
  `addAllowedApplication`/`addDisallowedApplication` are the *VPN app itself* choosing which
  apps route through *its own* tunnel — the mechanism Focus Fence already uses for excluded
  apps — not a coexistence mechanism between two VPNs.
- **No third-party per-app routing** exists outside that: an ordinary app cannot route another
  app's traffic (or just its DNS) through a tunnel while a different app holds the VPN slot.
- **The one genuine exception is profile separation**, and it doesn't help: a work profile can
  run its own VPN alongside the personal profile's, because profiles are separate Android
  users. *(Labeled: platform knowledge, not quoted from this page.)* Focus Fence and the
  user's other VPN live in the same profile, so this changes nothing for D-013.

## Consequence

D-013's premise is confirmed as a permanent platform constraint, with a primary-source
citation replacing the standing assumption. The design answer stays what `approach.md` B4
already says: enforcement must *survive* losing the slot (accessibility-layer rules keep
working; DNS-layer schedules stop — the known gap), and `onRevoke()` handling plus honest UI
about what still works are the mitigation, not any coexistence trick. The on-device
coexistence test in `approach.md` ("install PCAPdroid/WireGuard, see what still enforces")
remains worth one afternoon — this research confirms the constraint, not the app's observed
behaviour under it.

Nothing here opens new design options, and that is the finding: the queue expected "no, one
slot, period," and that is what the current documentation says.
