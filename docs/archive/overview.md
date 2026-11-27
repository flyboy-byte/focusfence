# Overview

> **Tier:** High-level · **Audience:** decision-maker, and anyone hearing about this
> project for the first time · **Use when:** deciding whether/how to pursue this, or
> explaining it to someone new in under 5 minutes.
> See [`documentation-guide.md`](./documentation-guide.md) for how this fits with the
> rest of the packet.

## One-line summary

A general-purpose FOSS Android tool for self-imposed digital boundaries, designed from a specific
power-user/developer philosophy rather than trying to satisfy every blocking use case.

## Who/what it's for

Someone who owns their phone, knows certain apps or sites are bad for their attention or
wellbeing, and wants stronger and more customizable boundaries than stock Digital Wellbeing gives
them — **without installing parental-control software that treats them like a managed child.**

The design anchor is a technically capable user. In @flyboy-byte's words (recorded 2026-08-14):

> I want to remain the real owner of the device. I may be an Android developer or power user who
> needs ADB, sideloading, PCAPdroid, another VPN, networking experiments, or temporary
> unrestricted access. Focus Fence should help prevent **impulsive bypass**, not fight a
> deliberate owner who is intentionally working on their phone.

Built around those needs first, but usable by normal users through sensible defaults.

**The middle position is load-bearing, and both ways of collapsing it are wrong.** Read as "this
is for one person," every abstraction overfits to one workflow. Read as "a general digital
wellbeing app," it drifts into copying whatever features competitors have. The correct reading:
the personal use case supplies the philosophy and the constraints; the implementation generalises
those principles to other people who want the same kind of control.

Explicitly **not** trying to be:

- **A VPN.** The tunnel routes two host addresses and carries no user traffic anywhere.
- **A cloud product.** No account, no policy server, no sync, no telemetry — ever.
- **A content filter for someone else.** It is a tool a user points at themselves. Strict mode is
  friction the user chose, not enforcement against a third party.
- **An ad blocker** competing with uBlock Origin or AdAway. Ad blocking is one toggle over
  machinery that exists for other reasons.
- **A firewall.** Full-TUN packet filtering would make it a worse NetGuard and discard the one
  property that makes it cheap.

## Boundaries are the product

Focus Fence is not fundamentally "an ad blocker", "a porn blocker", "an app timer" or "a VPN".
Those are **capabilities that may support a user-defined boundary**. The user expresses the
boundary; the app picks the mechanism. Six examples, which double as the project's acceptance
criteria:

1. Block adult-content domains all the time
2. Let me use YouTube but not Shorts
3. Block Reddit until after work
4. Limit Instagram to 20 minutes
5. Make TikTok unavailable during a commitment period
6. Optionally block ads and trackers, because the DNS filtering layer already exists

## Why this approach (vs. the obvious alternative)

- **DNS-only routing instead of a full tunnel.** The VPN adds `/32` and `/128` routes for its own
  two synthetic DNS servers; everything else takes the ordinary network path and never enters the
  process. This is why battery cost is negligible, and it means the app never has the *ability*
  to see user traffic — a privacy property enforced by architecture rather than by promise.
- **Least invasive mechanism that can enforce a given boundary.** Privacy means more than "we
  don't upload data" — the app should avoid *observing* information it doesn't need in the first
  place. More privilege is not automatically better.
- **Friction calibrated to an impulse, not to an adversary.** The threat model is "I decided at
  9 PM I don't want this available, and at 11:30 PM I impulsively try to undo it." Not an owner
  with ADB, recovery, root and unlimited effort. This gives a stopping rule that most blocking
  apps lack, and it's why Device Owner mode is rejected.
- **The VPN stays optional and disposable.** The rest of the app is meant to keep working when
  PCAPdroid, WireGuard or a development experiment owns Android's single VPN slot. Most
  competitors treat their VPN as mandatory.

## What success looks like

Two things, in order:

1. **A GitHub Release that a stranger can download and install** — a signed APK with a license,
   honest documentation, and a version history. Not a CI artifact for two people. Today the
   release build produces `app-release-unsigned.apk`, which Android will not install, and the
   repo has no `LICENSE`, so nobody may legally use it either.
2. **All six boundary examples above are expressible in the app.** Currently two are impossible
   and three are partial — see `approach.md`.

## What's genuinely novel vs. applying an existing pattern

Worth being honest, because the research in [`audits/`](audits/) surveyed the field and found
most of this territory occupied.

**Not novel:** DNS-based domain blocking via a local `VpnService` is well-trodden — DNS66,
personalDNSfilter, RethinkDNS all do it. Hosts-file ad blocking is a solved commodity.
Accessibility-service URL filtering is a known technique. None of the individual mechanisms here
are new.

**Genuinely distinctive:** the *combination* and the *restraint*. Specifically —

- Layering per-path section rules (allow `facebook.com/marketplace`, block the rest) on top of
  DNS blocking, which requires deliberately leaving the domain resolvable and moving enforcement
  to a different layer. That interaction is not obvious and most tools don't attempt it.
- Inferring website usage time from DNS activity, gated on screen-on and "front door" hostnames.
  It's a clever low-observation trick that gets budget tracking for almost no additional
  observation. It is also an *estimate*, and should always be labelled as one.
- Treating "don't fight the device owner" as a design constraint rather than a weakness. The
  self-control category generally competes on how hard it is to escape; this project deliberately
  competes on the opposite axis.

The novelty is a coherent philosophy applied consistently, not a new mechanism. That's worth
saying plainly rather than overselling.
