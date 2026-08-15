# Design — the boundary model

> **Tier:** Low-level (build detail) · **Audience:** whoever implements Track B ·
> **Use when:** deciding whether B2 is real, and what B1–B4 actually consist of.
> Assumes [`overview.md`](./overview.md) and [`approach.md`](./approach.md) have been read.

This is design reasoning, not a validated result. Where a conclusion depends on an unverified
external fact it says so and points at [`research-handoff.md`](./research-handoff.md). Nothing
here has been run on a device.

The question it exists to answer is **O-004: is the boundary model real, or is it the existing
rules with a wrapper?**

The answer is that it's real, but for a different reason than expected — and the reasoning
surfaced two things that change the plan.

---

## 1. What the current model actually is

A `BlockRule` is described as "exactly one of four modes":

| Mode | Encoding |
|---|---|
| Always | `reddit.com` |
| Daily window | `reddit.com\|540\|1080` |
| Daily budget | `reddit.com\|b\|30` |
| Section allowlist | `facebook.com\|p\|/marketplace` |

Read carefully, those four aren't four kinds of thing. They're **two independent dimensions that
have been flattened into one enum**:

- *What it applies to* — always a single domain. Never an app, never a category.
- *When it applies* — always / inside a window / once a budget is spent.
- Plus one **exception** mechanism (allowed paths) wearing the costume of a fourth mode.

So the real shape hiding in there is `(target, condition, exceptions)`. The current encoding can
only express `target = one domain`, and it can only express one condition at a time because the
condition is fused to the mode tag.

Apps escaped the model entirely: `BlockedAppsStore` is a bare `Set<String>` of package names, with
no condition dimension at all.

**That's why two of the six example boundaries are inexpressible.** Not because the model is too
small, but because it multiplies out to a matrix the encoding can't represent — and half the
matrix is missing.

## 2. The model

```
Boundary {
  id: String
  name: String                  // user-facing: "Evenings off social"
  targets:    Set<Target>       // what it applies to
  condition:  Condition         // when it applies
  exceptions: Set<Exception>    // carve-outs
}

Target     = Domain(name) | App(pkg) | Source(id) | Category(id)
Condition  = Always | Window(startMin, endMin) | Budget(minutes) | Commitment(untilEpoch)
Exception  = PathPrefix(domain, path) | AllowDomain(name)
```

Four closed variants each. **This is deliberately not a policy language** — no expressions, no
composition, no rule engine (D-014). It's the product of two small fixed sets, which is exactly
the thing the current encoding can't represent and nothing more.

The existing four modes map onto it losslessly:

| Today | Becomes |
|---|---|
| `reddit.com` | `targets=[Domain(reddit.com)]`, `condition=Always` |
| `reddit.com\|540\|1080` | `condition=Window(540,1080)` |
| `reddit.com\|b\|30` | `condition=Budget(30)` |
| `facebook.com\|p\|/marketplace` | `condition=Always`, `exceptions=[PathPrefix(facebook.com,/marketplace)]` |
| `blocked_apps` set | one `Boundary` per app, `condition=Always` |

### Does it survive the six examples?

| # | Boundary | Expressed as |
|---|---|---|
| 1 | Adult content always | `targets=[Category(adult)]`, `Always` |
| 2 | YouTube but not Shorts | `targets=[Domain(youtube.com), App(…youtube)]`, `Always`, `exceptions=[PathPrefix(youtube.com,/) minus /shorts]` |
| 3 | Reddit until after work | `targets=[Domain(reddit.com)]`, `Window(0,1020)` |
| 4 | Instagram 20 minutes | `targets=[Domain(instagram.com), App(com.instagram.android)]`, `Budget(20)` |
| 5 | TikTok during a commitment | `targets=[Domain(tiktok.com), App(…musically)]`, `Commitment(until)` |
| 6 | Ads and trackers | `targets=[Source(stevenblack)]`, `Always` |

All six fit, and **the thing that makes them fit is `targets` being a set that can hold apps and
domains together.** That's the single most important property of the model: a boundary is one
user-facing object even though it needs two enforcement mechanisms. "No TikTok tonight" is one
decision, not a website rule plus an app rule that can drift apart.

Example 2 is the awkward one and the model shouldn't pretend otherwise — see §5.

### So: wrapper or not?

**Not a wrapper.** A wrapper would leave the expressible set unchanged. This changes it, because
`targets: Set<Target>` and a `condition` that applies to apps are both new capability, not new
syntax. The four existing modes collapsing to one shape is a *symptom* of that, not the point.

But the counter-argument from `approach.md` partly survives, and it's the finding below.

---

## 3. The finding that reorders the plan

Enumerate the matrix and ask what enforces each cell:

| Target × Condition | Mechanism | Feasible today? |
|---|---|---|
| Domain × Always | DNS block | ✅ ships |
| Domain × Window | DNS + clock | ✅ ships |
| Domain × Budget | DNS + usage inference | ✅ ships (an estimate) |
| Domain × Commitment | DNS + clock, edit-locked | ✅ trivial from Window |
| Domain × exceptions | must stay resolvable; accessibility reads the URL | ✅ ships |
| **App × Always** | accessibility bounce | ✅ ships |
| **App × Window** | accessibility bounce + clock | ⚠️ **~nothing needed** |
| **App × Commitment** | accessibility bounce + clock | ⚠️ **~nothing needed** |
| App × Budget | needs real foreground time | ❌ needs `UsageStatsManager` |
| Source/Category × any | needs the source model | ❌ needs B6 |

Look at `UrlFilterService`:

```kotlin
if (eventPackage in blockedApps) {
    performGlobalAction(GLOBAL_ACTION_HOME)
```

The service already wakes on the right event, for the right package, and already holds a
`@Volatile` snapshot refreshed by the prefs listener. Making app blocking time-aware is replacing
a set membership test with a condition evaluation — **against a condition evaluator that already
exists for domains** in `DnsVpnService.ruleBlocks()`.

**Example 5 — "make TikTok unavailable during a commitment period" — is currently listed as
impossible, and it is one of the cheapest things on the entire plan.** No new permission, no
UsageStats, no Shizuku, no VPN. It needs the condition logic to be reachable from the accessibility
service, which is precisely what B1 (moving policy into `dns/`) delivers.

The same is true of app windows. Only app *budgets* genuinely need a new capability, because
counting foreground minutes is a different problem from noticing a window opened.

### What this changes

`approach.md` sequences B1 → B2 → B3. The reasoning says **B1 → B3a → B2 → B3b**:

- **B1** — move condition evaluation into `dns/` behind an injected clock. Unchanged, still the
  keystone, still no behaviour change.
- **B3a** *(new, small)* — give apps `Window` and `Commitment` by evaluating conditions in the
  accessibility service. Closes example 5 and half of example 3's app equivalent. No new
  permission. Probably a day.
- **B2** — the full boundary object, `targets: Set<Target>`, migration, UI.
- **B3b** — app budgets via `UsageStatsManager`, which needs `PACKAGE_USAGE_STATS`.

That ordering delivers a user-visible capability *before* the expensive data-model change, and it
de-risks B2 by proving the condition-evaluation split works in both services first.

It also sharpens O-004's fallback: if B2 stalls, **B3a alone closes the most-missed gap**, which is
a materially better fallback position than `approach.md` described.

---

## 4. Graceful degradation without the VPN

D-013 promises the rest of the app keeps working when another VPN owns Android's single slot.
Reading the code says domain boundaries die and app boundaries survive, because the former are
enforced in `DnsVpnService` and the latter in the accessibility service.

But there's a mechanism already present that the design overlooked. `UrlFilter.shouldBlock`
contains:

```
Rules without path exceptions return false — the DNS layer enforces those.
```

That's correct while the VPN is up. **When the VPN is down it's the wrong answer** — the
accessibility service is reading the browser's address bar anyway, and the host is right there. It
could enforce domain boundaries in browsers with no additional observation whatsoever.

So a domain boundary could degrade rather than fail:

| VPN | Browser traffic | In-app / SDK traffic |
|---|---|---|
| Up | DNS block | DNS block |
| Down | accessibility URL block | not enforced |

That covers the case a self-control tool actually cares about — a person opening reddit.com in
Chrome — while honestly not covering traffic no mechanism can see without the tunnel.

**Two costs, and they're real:**

1. It needs `TYPE_WINDOW_CONTENT_CHANGED` for browsers whenever any domain boundary exists, not
   only when section rules exist. That's the high-volume event type the service currently gates
   deliberately for battery (see `architecture.md`). Mitigation: request it only while the VPN is
   actually down, which the service can know — turning a permanent cost into a conditional one.
2. It's a *silent* partial enforcement, and `risks.md` flags silent failure as worse than a crash
   in a tool people rely on. The UI has to say plainly which boundaries are fully enforced and
   which are browser-only right now.

Feasibility: high. It reuses `Blocklist.findRule` and the existing address-bar reading. The
condition evaluator from B1 is the prerequisite, again.

---

## 5. Where the model is honest about not working

**Example 2 (YouTube but not Shorts) does not fully fit, and shouldn't be made to.** Section rules
work by reading a browser's address bar. Inside the YouTube app there is no address bar, no URL,
and no path — the concept the exception is expressed in doesn't exist. Enforcing it there means
recognising UI elements per app version, which is a per-release compatibility burden on the most
frequently updated apps on the platform, and `risks.md` already names browser view IDs as the least
stable dependency in the project.

The model should represent this rather than paper over it: an `App` target with `PathPrefix`
exceptions is **not enforceable**, and the UI should say so at the point the user creates it
instead of silently doing nothing. Treating "browser-only" as a first-class, visible property of a
boundary is more useful than pretending coverage is uniform.

**Commitment vs. strict mode.** These are two locking concepts and they must not become two lock
systems. Reasoning: keep `StrictLock` exactly as it is — it guards *edits to configuration* and is
global. `Commitment(until)` is a **condition**, not a second lock; what makes it binding is that
the strict machinery refuses to shorten it while locked. One state machine, one unlock path, one
place where D-012's 10-minute delay lives.

**Budgets stay estimates.** DNS-inferred website time undercounts against long-lived caches and
overcounts a tab left open. Nothing in this model fixes that, and `UsageStatsManager` only helps
for apps. The honest move is labelling it in the UI, not improving the inference.

---

## 6. Migration

Low risk, and worth stating because `notes.md` flags it as an open question with real installs at
stake.

The existing rules are a `StringSet` of encoded strings; blocked apps are a second `StringSet`.
Every one of them maps to exactly one `Boundary` with a single target, by the table in §2. The
transform is total, deterministic and lossless — there is no old state that fails to convert, and
no user choice to preserve that the new model can't hold.

Practical shape: write boundaries under a new key, leave the old keys untouched on first migration
so a downgrade doesn't destroy anything, and remove them a release later. The prefs-listener
propagation mechanism (D-001's neighbour, `architecture.md` § Configuration propagation) is
unchanged — one more key in the same `focusfence` file, handled in both existing listeners.

The one thing to get right: **`AdListStore` writes `KEY_COUNT` last as its reload signal.** Any new
multi-key write needs the same discipline, or a service can read a half-written boundary set.

---

## 7. What this reasoning did not establish

Flagged rather than assumed, per the packet's rules:

- **That the R8 release build runs.** Compiles; never installed.
- **`UsageStatsManager`'s real accuracy** for foreground time, and the `PACKAGE_USAGE_STATS` grant
  flow. Queue item 4. Gates B3b only.
- **Whether Shizuku package suspension works as assumed** on a non-root device in 2026. Queue item
  3. Gates B7; the model is indifferent — it's an enforcement swap behind the same `App` target.
- **Whether Android offers any VPN coexistence mechanism.** Queue item 8. If it does, §4 is moot;
  if not (expected), §4 is the right design.
- **Battery cost of the §4 conditional event-type change.** No harness exists (B8). This is the one
  place the design proposes something whose cost genuinely can't be argued without measurement.

## Bottom line

The boundary model is worth building: it changes what's expressible, not just how it's written, and
all six examples fit a fixed four-by-four shape with no policy language.

But **the reasoning demoted it in the ordering.** The most-missed capability — time-bounded app
blocking — turns out to need almost nothing, because the accessibility service already runs the
right code at the right moment and only lacks access to a condition evaluator. Do B1, then that,
then the data model.
