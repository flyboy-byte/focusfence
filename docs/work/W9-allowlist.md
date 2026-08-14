# W9 — User allowlist

**Track:** Product (B5) · **Depends on:** W6 · **Blocks:** W11 · **Status:** Spec draft
**Size:** ~half a day. Small, high value per line.

## Why

There is no allowlist anywhere in the codebase. `blockReason()` checks user rules, then the ad-host
set, then the DoH guards, and **there is no path by which a user can exempt a domain from a source
that blocks it.**

In practice: a single false positive in the ~100k-entry downloaded hosts file can only be resolved
by turning the entire ad list off. Fine for two maintainers who can read the source; a support
burden the moment there are real users, and an outright blocker for W11 — the instant a second list
exists, false positives stop being hypothetical.

## Design

New store `AllowlistStore` in `data/`, backed by a `StringSet` in the **`focusfence`** prefs file so
the existing listeners propagate it. Normalize every entry through `Blocklist.normalize` — never
trust raw input at a store boundary.

`DnsVpnService` holds a `@Volatile` snapshot refreshed by its existing prefs listener, exactly like
`rules` and `adHosts`.

Evaluation goes at the **top** of `blockReason()`:

```kotlin
private fun blockReason(qname: String): String? {
    if (Blocklist.matches(qname, allowlist)) return null   // see O-003 before changing this
    val rule = Blocklist.findRule(qname, rules)
    ...
}
```

Use `Blocklist.matches`, which walks parent domains — so an allowlist entry for `example.com` also
exempts its subdomains, consistent with how rules already work.

### The one real decision — O-003

**Should an allowlist entry override a *user rule*, or only list-sourced blocks?**

The conservative answer, and the recommended default: **only list-sourced blocks.** An allowlist
should never silently defeat a boundary the user deliberately set — that would turn it into a
one-tap bypass of the app's entire purpose, which fails the threat model.

If that's the choice, the placement above is wrong and the check belongs *after* the user-rule
branch instead:

```kotlin
val rule = Blocklist.findRule(qname, rules)
if (rule != null && ruleBlocks(rule, qname)) return rule.domain
if (Blocklist.matches(qname, allowlist)) return null
if (adHosts.isNotEmpty() && qname in adHosts) return StatsCodec.KEY_ADS
if (Blocklist.matches(qname, Blocklist.DOH_BYPASS_GUARDS)) return StatsCodec.KEY_GUARD
```

**Resolve O-003 and record it in `DECISIONS.md` before implementing.** The two placements differ by
three lines and produce completely different products.

### DoH guards

Decide explicitly whether the allowlist can exempt a DoH bootstrap host. Argument for: the project
respects device ownership, and a user who deliberately wants DoH should be able to say so.
Argument against: it's a bypass of the filter's own integrity. Recommendation: allow it, but warn
in the UI at the point of adding, since the user is disabling their own boundary.

### UI

An "Allowed domains" section in `BlocklistFragment` or `SettingsFragment`: add, list, remove. Reuse
the existing domain-entry patterns, including `Blocklist.normalize` on input so a pasted URL works.

Strict mode: adding an allowlist entry is *loosening*, so it must check `StrictStore.isLocked` and
route through `UiUtil.showStrictLockedDialog`.

## Tests

- Allowlisted domain is not blocked by the ad-host set.
- Allowlisted parent domain exempts a subdomain.
- Per O-003: a user rule either is or is not overridden — test the decided behaviour explicitly.
- Normalization: a pasted full URL becomes a bare domain.
- Empty allowlist changes nothing (no regression to existing block paths).

## Acceptance criteria

- [ ] O-003 recorded as a resolved decision in `DECISIONS.md` before code lands.
- [ ] `AllowlistStore` writes to the `focusfence` prefs file and both services' listeners are
      updated if they consume it.
- [ ] Allowlist entries are normalized via `Blocklist.normalize` at the store boundary.
- [ ] Adding an entry while strict mode is locked is refused.
- [ ] `./gradlew testDebugUnitTest` passes with the new cases.

## Out of scope

- Per-source allowlisting (needs W11's source model).
- Importing/exporting allowlists.
- Automatic false-positive detection.
