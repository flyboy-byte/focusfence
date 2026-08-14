# Framework — Focus Fence

> **Tier:** Meta (self-aware — this doc describes and tracks the project it's part of)
> **Audience:** you · **Use when:** you don't know what to do next, or you're checking
> in on progress after time away.

## What this is

Focus Fence is an existing, working Android app (v2.0.1, 11 commits) that its author built
before any of this planning material existed. This packet came later, in three steps: a batch of
external research was commissioned and then verified line-by-line against the source
([`docs/audits/`](docs/audits/)); the codebase was documented as-built
([`docs/architecture.md`](docs/architecture.md)); and on 2026-08-14 the product intent was
finally written down by @flyboy-byte ([`docs/overview.md`](docs/overview.md)).

This file turns that into a staged process with honest status. Each phase advances by *doing*
something outside these documents — a device test, a real decision, a signed build — not by
writing more docs.

**Focus Fence sits in an unusual position in the phase model:** most of Phase 3 (Build) is
genuinely done, while Phase 2 (Validation) and Phase 5 (Formalize) have barely started. It was
built first and scoped afterwards. Don't read the unchecked Phase 2 boxes as "nothing works" —
read them as "nothing has been checked against reality yet."

Update the checkboxes below as things actually happen.

## Phase model

```
Phase 0        Phase 1        Phase 2         Phase 3      Phase 4       Phase 5
Capture   ──►   Scoping  ──►   Validation ──►  Build   ──►  Verify  ──►  Formalize
```

### Phase 0 — Capture

- [x] Source material captured — the codebase itself, plus five commissioned research documents
      in [`docs/audits/`](docs/audits/) (merged 2026-08-13, PR #9)
- [x] Read and understood well enough to extract the real open questions — every code-level claim
      in the research was verified against source in
      [`docs/audits/findings.md`](docs/audits/findings.md); two of six of the project's own
      example boundaries turned out to be inexpressible

**Gate to Phase 1:** none. Satisfied.

### Phase 1 — Scoping

- [x] [`docs/overview.md`](docs/overview.md) — what it is and why, including the product intent
      recorded 2026-08-14
- [x] [`docs/approach.md`](docs/approach.md) — is this worth doing this way; alternatives
- [x] [`docs/risks.md`](docs/risks.md) — scope/dependency/legal + technical/operational
- [x] [`docs/infrastructure.md`](docs/infrastructure.md) — what building and *releasing* it requires
- [x] [`docs/notes.md`](docs/notes.md) — open decisions log
- [x] [`docs/documentation-guide.md`](docs/documentation-guide.md) +
      [`docs/research-handoff.md`](docs/research-handoff.md)
- [x] [`DECISIONS.md`](DECISIONS.md) — numbered log of *why*, D-001 … D-014

**Gate to Phase 2:** none blocking. What's *not* done is anything that required leaving these
files.

### Phase 2 — Validation

**Design-reasoning first, empirical checks second** (decided 2026-08-14). The app already exists
and its behaviour is readable from source, so most questions here are answerable by reasoning
carefully rather than by instrumenting anything. Device work is deferred until a design decision
actually depends on it.

- [x] **Reason the boundary model through against the six examples** — resolves O-004. See
      [`docs/design.md`](docs/design.md). Conclusion: the model is real (it changes what's
      expressible, not just the syntax), but the reasoning **reordered the plan** — time-bounded
      app blocking turns out to be nearly free and should land before the data-model change.
- [x] Reason through what survives without the VPN, from source. Domain boundaries die, app
      boundaries live — and `docs/design.md` §4 finds a degradation path that was overlooked.
- [ ] Run the [`docs/research-handoff.md`](docs/research-handoff.md) queue through deep research
      and merge findings back. Nine open items, all externally verifiable, none of which this
      session should guess at. Only items 3, 4 and 8 gate design decisions.
- [ ] Install the release APK on a real device — the one check that reasoning cannot substitute
      for, since R8 failures only appear at runtime. Cheap, and it's also Track A's gate.
- [ ] *(deferred)* VPN-coexistence device test. The code answers it; a device would only confirm.
      Worth doing before claiming it in user-facing docs, not before building against it.

**Gate to Phase 3:** the design questions blocking a build are reasoned through and written down.
**Satisfied for B1/B3a** — those are now buildable. Not satisfied for B3b, B6 or B7, which depend
on research-queue items 2, 3 and 4.

### Phase 3 — Build

Mostly pre-existing. Honest status:

- [x] Core architecture in place — DNS-only VPN, accessibility service, DoH guards
- [x] End-to-end working app: domains, schedules, budgets, section rules, strict mode, app
      blocking, stats, onboarding
- [x] Pure `dns/` logic unit-tested on the JVM (8 test classes)
- [x] `assembleRelease` succeeds with R8 (verified 2026-08-14)
- [ ] **Track A — release mechanics.** LICENSE, signing config, CI release build, release
      workflow, changelog, README corrections. See `docs/infrastructure.md`.
- [ ] **Track B — the boundary gap.** B1 → B3a → B2 → B3b → B4 … B8. Design reasoned through in
      [`docs/design.md`](docs/design.md); sequencing in `docs/notes.md`. **B1 and B3a are
      buildable now** — no open question blocks them.
- [ ] No instrumented tests exist; the VPN and accessibility services have zero automated
      coverage

**Gate to Phase 4:** a signed APK that installs and works.

### Phase 4 — Verify

- [ ] Release APK verified on a real device, not just built
- [ ] Battery measured rather than asserted — no benchmark harness exists anywhere in the tree,
      and battery is an explicit product requirement, so no claim can currently be made as a
      *result*
- [ ] Remaining gaps documented plainly — in progress; `docs/risks.md` and the "Not yet done"
      section below

**Gate to Phase 5:** confident enough to hand the APK to a stranger.

### Phase 5 — Formalize

- [ ] Decide the license (O-001) — blocks everything else here
- [ ] Decide signing key custody (O-002) — a one-way door; the key becomes the app's permanent
      identity
- [ ] Tag and cut the first GitHub Release with a signed APK and published SHA-256
- [ ] Decide whether F-Droid is wanted, which would put a third party in the build path and
      materially help the trust problem in `docs/risks.md`

## Current status (update this line as phases advance)

**Phase 1 complete; Phase 2 design reasoning done; Phase 3 partially pre-satisfied.** The single
highest-leverage next action is **B1 — move condition evaluation out of `DnsVpnService` into
`dns/` behind an injected clock.** No behaviour change, existing tests to lean on, and it's the
prerequisite for B3a (time-bounded app blocking, which `docs/design.md` found to be nearly free
and which closes the most-missed gap), for the VPN-down degradation path in `design.md` §4, and
for most of the rest of Track B.

The two decisions that block the release (license O-001, signing key custody O-002) need
@sidewinderzz and are worth raising in parallel, since neither requires any code.
