# W12 — Shizuku package suspension as preferred app enforcement

**Track:** Product (B7) · **Depends on:** W7 · **Status:** Spec draft — **needs research Q3**
**Size:** ~1 week

> **2026-08-15:** originally specced against W8 (the unified boundary model), which is now
> parked (`docs/parked.md`, `DECISIONS.md` D-016 recommendation). W7 already made `App` a
> first-class rule target — that's all this order actually needs. Re-read as depending on W7.

## Why — this is a privacy improvement, not a power feature

The external research files Shizuku under "advanced capabilities." That framing is wrong for this
project. Under the recorded principle *use the least invasive mechanism that can enforce a
boundary* (D-010), Shizuku is the **less invasive** option, and the current mechanism is the app's
single largest observation surface.

Today, whole-app blocking is enforced by the accessibility service, which means Focus Fence reads
**browser address bars — full URLs, across ten browsers** — and watches window events. Shizuku
package suspension enforces harder while observing almost nothing.

For *section rules* the accessibility service is genuinely necessary: DNS sees hostnames, TLS hides
paths, so nothing cheaper can see `/shorts`. For *whole-app blocking* it is not.

It also fits the threat model well (D-012): non-root Shizuku must be restarted after each reboot,
which makes it naturally reversible and keeps it firmly on the "friction, not prevention" side.

## Blocked on research

Queue item 3: what Shizuku can actually do on a **non-root** device as of 2026 — specifically
package suspend/disable — what permissions it needs, how much setup friction the user faces, and
whether the post-reboot restart requirement still holds.

The whole work order depends on the answer. If suspension isn't available without root, this
becomes a footnote rather than a feature.

## Sketch

- Shizuku is an **optional capability**. The app must work fully without it; this changes the
  enforcement mechanism for `App` targets, nothing else.
- Because W7 already made `App` a first-class rule target with its own evaluation path
  (`RuleEvaluator.blocksApp`), this should be a mechanism swap in `UrlFilterService` rather than a
  rewrite. If it isn't, something about W7's shape doesn't hold — report that.
- Precedence: Shizuku when available and granted, accessibility bounce otherwise. The user should
  be able to see which is in use.
- When Shizuku handles app blocking and no section rules exist, the accessibility service's
  `packageNames` can shrink to nothing — a measurable reduction in what the app observes, and worth
  stating in the UI as a privacy benefit.
- Onboarding must explain plainly what Shizuku is, what it grants, and that it needs re-starting
  after a reboot. Do not bury this.
- Handle the reboot case gracefully: if Shizuku is unavailable, fall back to accessibility rather
  than silently not enforcing. **Silent non-enforcement is the worst failure mode in this app.**

## Acceptance criteria

- [ ] Research Q3 answered and merged before implementation.
- [ ] The app works fully with Shizuku absent, unavailable, or denied.
- [ ] Enforcement falls back to accessibility automatically, and the UI shows which mechanism is
      active.
- [ ] With Shizuku active and no section rules configured, the accessibility service registers for
      no third-party packages — verified by inspecting `serviceInfo.packageNames`.
- [ ] Permission and capability explanations are in `res/values/strings.xml` and shown before the
      user is asked to set anything up.
- [ ] `./gradlew testDebugUnitTest` passes.

## Out of scope

- Root support.
- Device Owner provisioning — rejected (D-012): it fights a determined owner, and the stated
  adversary is an impulse.
- Using Shizuku for anything beyond app enforcement.
