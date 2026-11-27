# W8 — The boundary model

**Track:** Product (B2) · **Depends on:** W6, W7 · **Status:** 🔴 **Parked, 2026-08-15 — do not
start without a human reopening it.** See `docs/parked.md` and the recommendation in the project
review: W7 already captures most of the value of giving apps first-class rule targets, at a
fraction of this order's cost. W10 and W12 no longer depend on this — both were re-scoped to
depend on W7 instead.
**Size:** The largest item on the plan — weeks, not days. Read this whole file before starting,
if it's ever reopened.

## Why

The product's premise is that **boundaries** are the thing, and ad blocking, timers and the VPN are
mechanisms that serve one. The code has no boundary object. "No TikTok during a commitment period"
is today three unlinked settings in three stores with no shared identity, and nothing can answer
"what am I currently committed to?"

Reasoning in `docs/design.md` established the model is real capability, not a wrapper: the current
four "modes" are two independent dimensions (target × condition) flattened into one enum, and the
encoding can only express `target = one domain`.

## The model

```
Boundary {
  id: String
  name: String                  // user-facing: "Evenings off social"
  targets:    Set<Target>
  condition:  Condition
  exceptions: Set<Exception>
}

Target     = Domain(name) | App(pkg) | Source(id) | Category(id)
Condition  = Always | Window(startMin, endMin) | Budget(minutes) | Commitment(untilEpoch)
Exception  = PathPrefix(domain, path) | AllowDomain(name)
```

**Four closed variants each. This is not a policy language** — no expressions, no composition, no
rule engine. That restraint is a recorded decision (D-014); a general engine before the abstractions
have proved themselves is explicitly rejected.

The decisive property is `targets` being a **set that can hold apps and domains together**, so one
user decision stays one object instead of two settings that drift apart.

### Mapping from today

| Today | Becomes |
|---|---|
| `reddit.com` | `targets=[Domain(reddit.com)]`, `Always` |
| `reddit.com\|540\|1080` | `Window(540,1080)` |
| `reddit.com\|b\|30` | `Budget(30)` |
| `facebook.com\|p\|/marketplace` | `Always` + `exceptions=[PathPrefix(facebook.com,/marketplace)]` |
| app rule from W7 | `targets=[App(pkg)]` + that rule's condition |

## Migration

The transform is **total, deterministic and lossless** — every existing rule maps to exactly one
boundary with a single target. There is no old state that fails to convert.

Required approach:

1. Write boundaries under a **new prefs key** in the same `focusfence` file.
2. **Leave the old keys in place** on first migration, so a downgrade or a bug doesn't destroy user
   configuration. Remove them a release later.
3. Write the "boundary count" or equivalent key **last**, mirroring `AdListStore`'s discipline —
   that final write is the reload signal, and a service must never observe a half-written set.
4. Both services' prefs listeners must handle the new key.

There are real installs. A migration that silently drops rules in a tool people rely on to hold a
boundary is worse than most bugs.

## What it cannot do — build this in, don't paper over it

**An `App` target with `PathPrefix` exceptions is unenforceable.** Inside a native app there is no
address bar, no URL and no path — the concept the exception is expressed in does not exist.
Enforcing "YouTube but not Shorts" inside the YouTube app means recognising UI elements per app
version, which is a per-release compatibility burden on the most frequently updated apps on the
platform.

The UI must say so **at the point the user creates it**, not silently do nothing. Treat
"browser-only" as a first-class, visible property of a boundary.

**Commitment is a condition, not a second lock.** `StrictLock` stays exactly as it is — global,
guarding edits to configuration. What makes a commitment binding is that strict mode refuses to
shorten it. One state machine, one unlock path, one place where the 10-minute delay lives. Do not
build a second locking system.

**Budgets remain estimates.** DNS-inferred website time undercounts against long-lived caches and
overcounts a tab left open. Nothing here fixes that; label it in the UI.

## Sequencing guard

Do **not** start this before W6 and W7 have landed and been used. Those two prove the
condition-evaluation split works in both services, which is the risky part. If they surface a
problem, it is far cheaper to find it in a one-day change than inside this one.

If the model collapses in practice into "the same rules with a wrapper," **stop and report** —
the agreed fallback is that W7 alone closes the most-missed gap, and shipping that is a legitimate
landing place.

## Acceptance criteria

- [ ] All six boundary examples in `docs/overview.md` are expressible and enforceable to the extent
      the mechanism allows, each with a test.
- [ ] Migration is covered by unit tests for every existing encoded form, including bare package
      names and all four `BlockRule` modes.
- [ ] Old prefs keys still present and unmodified after migration.
- [ ] `grep -rn "^import android" app/src/main/java/app/focusfence/dns/` returns nothing.
- [ ] The UI marks browser-only boundaries visibly at creation time.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] No new periodic wakeup, timer or alarm is introduced.

## Out of scope

- `Source` and `Category` targets beyond the type existing — enforcement is W11.
- App budgets (W10).
- Shizuku (W12).
