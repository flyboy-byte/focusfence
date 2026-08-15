# W10 — App budgets via `UsageStatsManager`

**Track:** Product (B3b) · **Depends on:** W7, W8 · **Status:** Spec draft — **needs research Q4**
**Size:** ~2–3 days

## Why

W7 gives apps windows and commitments but explicitly **not** budgets, because counting foreground
minutes is a different problem from noticing a window opened. `RuleEvaluator.blocksApp` returns
`false` for budget rules with a comment pointing here.

"Limit Instagram to 20 minutes" is one of the six example boundaries, and today it only works for
the *website*, via DNS inference, and only while the VPN is up.

## Why `UsageStatsManager` rather than the existing approach

- It reports **real foreground time**, not an inference from network activity.
- It needs **no VPN**, so app budgets keep working when PCAPdroid or WireGuard owns Android's single
  VPN slot — which is a stated product promise (D-013).
- It observes **package and duration**, not content. That fits the least-invasive-mechanism
  principle (D-010) better than either DNS inference or reading the screen.

Cost: `PACKAGE_USAGE_STATS` is a special access permission the user grants through a Settings
screen, not a runtime dialog. It must be **optional** — the app must work fully without it, with
only app budgets unavailable.

## Blocked on research

Queue item 4 in `docs/research-handoff.md`: what `UsageStatsManager` actually reports, how the
grant flow works, and its known accuracy limitations for foreground time. **Do not implement from
assumption** — the query granularity and bucket semantics materially change the design.

## Sketch

- New `usage/AppUsageSource` in the Android layer that queries `UsageStatsManager` for today's
  foreground time per package.
- Keep the decision pure: extend `RuleEvaluator.blocksApp` to take `usedMillis`, mirroring
  `RuleEvaluator.blocks`, and let the caller supply it. `dns/` must not learn about Android.
- `UrlFilterService` queries usage when it encounters a budget rule for the event's package.
  Consider caching briefly — this runs on an accessibility event and must not do heavy work per
  event.
- Settings UI: explain the permission plainly, deep-link to the grant screen, and show budget
  controls as unavailable (not hidden) when it isn't granted.

## Coexistence with DNS-inferred website time

Two sources of "time spent" must not confuse the stats UI. Proposed split, to be confirmed:
**apps from UsageStats** (measured), **websites from DNS inference** (estimated), both labelled as
such in `StatsFragment`. This is a UI decision as much as a data one.

## Acceptance criteria

- [ ] Research Q4 answered and merged into this file before implementation.
- [ ] The app works fully without `PACKAGE_USAGE_STATS`; only app budgets are unavailable, and the
      UI says why.
- [ ] App budgets enforce correctly with the VPN **stopped**.
- [ ] `grep -rn "^import android" app/src/main/java/app/focusfence/dns/` returns nothing.
- [ ] No per-event heavy query — usage lookups are cached or throttled.
- [ ] Stats UI distinguishes measured app time from estimated website time.
- [ ] `./gradlew testDebugUnitTest` passes.

## Out of scope

- Replacing DNS-inferred website timing. It stays; nothing cheaper can measure website attention.
- Per-app usage history or charts beyond what `StatsFragment` already does.
- Using UsageStats for anything other than budgets.
