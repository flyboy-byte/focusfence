# W1 — Correct three false documentation claims

> **Update 2026-08-15:** all three README corrections were made during a full README rewrite
> (commit on `dev`). **What remains of this work order is the two code comments** — the stale
> KDoc in `DnsVpnService.kt` and the provenance comment in `AdListStore.kt`. The README sections
> below are kept as the record of what was wrong and why.

**Track:** Release · **Depends on:** nothing · **Blocks:** nothing · **Status:** Ready
**Size:** ~30 minutes. Documentation and comments only — zero behaviour change.

## Why

Three statements in the repo describe the app inaccurately. Two are privacy/performance claims that
become claims made to strangers the moment there's a public release; one is stale text that already
caused real damage — it led five separate external research documents to describe the DNS block
path wrongly, and acting on them would have re-introduced a fixed battery regression.

## Background you need

Blocked DNS queries have **not** been answered with NXDOMAIN since commit `0552f11`. `Dns.buildBlockedResponse`
returns a real A/AAAA answer of `0.0.0.0` / `::` carrying an explicit TTL (1 hour for ads and DoH
guards, 60 seconds for user rules), or NODATA for other query types. `Dns.buildNxDomain` still
exists but is no longer on the block path. The reason is in its KDoc: a bare NXDOMAIN carries no
SOA record, so clients treat it as uncacheable and re-ask continuously, which was measured waking
the service thousands of times a day.

## Changes

### 1. `README.md` line 17 — the NXDOMAIN claim

Current:

```
  subdomains) are answered locally with `NXDOMAIN`, so the site simply fails to resolve.
```

Replace with wording that says: blocked queries are answered locally with a null address
(`0.0.0.0` / `::`) carrying a short TTL, so the site fails to connect; and that a cacheable answer
is used deliberately, because an uncacheable NXDOMAIN made clients re-query continuously and cost
battery.

### 2. `README.md` line 5 — the privacy overclaim

Current (lines 3–5):

```
using an on-device VPN. No root, no account, no external server: nothing about your browsing
ever leaves your phone.
```

"Nothing about your browsing ever leaves your phone" is not true: allowed DNS queries are forwarded
to Cloudflare `1.1.1.1` and Google `8.8.8.8` (see `DnsVpnService.kt:103-108`).

Replace with the precise claim, which is stronger: there is no account, no policy server and no
telemetry; all filtering decisions happen on the device; and ordinary DNS resolution for allowed
domains still goes to a public resolver, exactly as it would without the app.

### 3. `README.md` line 22 — the battery claim

Current:

```
impact is effectively zero. This is the same architecture used by well-known apps like
```

State the *reason* rather than the result: because only DNS packets are routed into the app and all
other traffic takes the normal network path, the design is expected to cost very little battery or
throughput. Do **not** assert a measured figure — no benchmark exists anywhere in the tree (that's
W13).

### 4. `DnsVpnService.kt:41-49` — the stale KDoc

Current:

```kotlin
 * which is why running this costs essentially no battery or throughput. Queries for
 * blocked domains are answered NXDOMAIN locally; everything else is relayed to a
 * real resolver over a protected socket.
```

Correct the NXDOMAIN sentence the same way as README change 1, and soften "costs essentially no
battery" to a design expectation rather than a measured fact.

### 5. `AdListStore.kt:16-18` — inaccurate provenance

The comment describes StevenBlack as aggregated from "the same sources AdGuard/uBlock use." That's
wrong: StevenBlack aggregates *hosts-format* sources, while uBlock Origin's defaults are
EasyList-family lists in a much richer syntax. Rewrite it to say what it actually is — a widely
used aggregated hosts file — without the comparison.

## Acceptance criteria

- [ ] `grep -rn "NXDOMAIN" README.md` returns nothing, **or** only text that correctly describes
      `buildNxDomain` as unused on the block path.
- [ ] `grep -n "nothing about your browsing" README.md` returns nothing.
- [ ] `grep -n "effectively zero" README.md` returns nothing.
- [ ] `grep -n "NXDOMAIN" app/src/main/java/app/focusfence/vpn/DnsVpnService.kt` returns nothing.
- [ ] `grep -n "AdGuard" app/src/main/java/app/focusfence/data/AdListStore.kt` returns nothing.
- [ ] `git diff --stat` shows changes **only** in `README.md`, `DnsVpnService.kt`, `AdListStore.kt`.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] No `.kt` change outside comments — verify with `git diff` that every Kotlin hunk is inside a
      comment or KDoc block.

## Out of scope

- Any code change whatsoever.
- The README's "Limitations" section — it is accurate.
- Adding a permissions/trust section (that's part of W5).
- Renaming or removing `Dns.buildNxDomain`. It's unused on the block path but harmless, and
  deleting it is a separate call.
