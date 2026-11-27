# W14 — Adult-content filtering: category lists + DNS SafeSearch

**Track:** Product · **Depends on:** W11 (multi-source lists) · **Status:** Spec draft — **needs research Q10**
**Size:** ~3–4 days for part 1, ~1 week for part 2 (SafeSearch is the harder half)

Governed by **D-016**. Read it before starting — it records what was rejected and why, and this
work order is not the place to relitigate the content-engine question.

## Why this is a category and not a feature

"Block adult-content domains all the time" is one of the six example boundaries in
`docs/overview.md` and is currently **impossible**: there is one list (StevenBlack base), no
categories, one on/off toggle.

It differs from the app's other boundaries in two ways that change the design:

- **The failure modes are asymmetric.** Over-blocking a social domain is annoying; under-blocking
  adult content is the whole failure. That justifies a stricter default here than anywhere else,
  and justifies making the toggle harder to reverse (strict mode).
- **The pull is stronger.** The impulse-circumvention threat model (D-012) still holds, but the
  impulse is more determined. Friction has to be real: a category that can be switched off in two
  taps at 1 a.m. does nothing.

## Part 1 — Category sources (the cheap 80%)

Almost entirely a configuration and UI problem once W11 lands, because the lists are hosts-format
and go straight into the existing `HashSet`.

- Add HaGeZi NSFW and StevenBlack's porn extension as `FilterSource` entries with
  `category = adult`.
- Surface an **Adult content** toggle in the blocklist UI, distinct from the ads toggle.
- Wire it as a `Category(adult)` target so it composes with conditions like everything else — a
  user can block it `Always` (the expected case) or on a window if they want.
- Attribution and licence per W11's research Q2. HaGeZi's terms must be confirmed before shipping;
  do not assume they match StevenBlack's MIT.

**Size note:** the adult category lists are large — HaGeZi's NSFW list is on the order of a
hundred thousand entries on its own. Memory with base + ads + adult enabled must be measured, not
assumed, and recorded in the PR body (same criterion as W11).

### The gap, and where it lives

Mixed-content platforms — Reddit, X, TikTok, Telegram, Discord, Imgur — serve adult and non-adult
content from the same hostnames, and nothing at DNS level separates them. **This work order does
not attempt to solve that**; the reasoning is parked in [`docs/parked.md`](../parked.md) P-001.

What W14 owes it is one sentence of honesty in the UI: the adult category covers dedicated adult
sites, and a platform like Reddit is blocked whole or not at all. Don't imply more. Someone will
rely on this.

## Part 2 — DNS SafeSearch enforcement (the interesting half)

This is what makes the feature more than a list, and it fits the existing architecture exactly.

Search engines expose alternate hostnames that force safe results. The filter can rewrite queries
to them:

| Query for | Rewrite to |
|---|---|
| `google.com` (and country TLDs) | `forcesafesearch.google.com` |
| `bing.com` | `strict.bing.com` |
| `duckduckgo.com` | `safe.duckduckgo.com` |

This closes the single largest hole a domain list leaves: image and video search, which surfaces
adult content from hostnames no blocklist can list.

### How it fits the DNS service

The service already relays non-blocked queries upstream over a `protect()`ed socket. SafeSearch is
a **third outcome** alongside "synthesize a block answer" and "relay unchanged": rewrite the
question name, relay, then rewrite the answer's name back so the client sees a response matching
what it asked. That last step matters — a resolver answering a name the client did not ask about
will be discarded.

Consequences to design for, not discover later:

- `Packets`/`Dns` currently parse enough to read a qname and synthesize a block. Rewriting requires
  **re-encoding a question and rewriting an answer's owner name**, including handling DNS name
  compression pointers in the response. This is the real cost of part 2 and the reason it is
  scoped separately from part 1. Budget for it.
- Keep the decision pure: a `dns/SafeSearch.kt` mapping (qname → replacement qname) in the
  Android-free package, with the packet surgery in the service. Same boundary as everything else.
- TTLs: honour the upstream answer's TTL. Do not apply the block-answer TTL split here.
- Failure must be safe. If the rewritten name fails to resolve, **do not** fall back to relaying
  the original — that silently disables the boundary, which D-012 calls the worst failure mode.
  Return a block answer instead and log it as a block.

### What SafeSearch does not do

It does not affect apps that talk to search backends over hardcoded IPs or their own domains, and
it does not affect a browser using DoH (the existing `DOH_BYPASS_GUARDS` handle that, and this
feature makes those guards load-bearing rather than incidental). Neither is a reason not to build
it; both are reasons to describe it accurately.

## Strict-mode interaction

Disabling the adult category, or turning SafeSearch off, must go through `StrictLock` when strict
mode is on — 10-minute delay, 5-minute window, same as loosening any other boundary. Enabling is
always immediate. This is the one place in the app where the friction is the feature.

Verify the check is actually added: `StrictLock` enforcement is spread across call sites, so a new
toggle does not inherit it.

## Blocked on research

**Queue item 10:** the current, correct SafeSearch hostnames for Google, Bing, DuckDuckGo and (if
applicable) Yandex/Brave; whether each still works in 2026; and how AdGuard Home / Pi-hole
implement the rewrite, since both have already solved the answer-name problem.

YouTube is **not** part of this work order — its restricted mode may require an HTTP header a DNS
filter cannot set, so it is parked in [`docs/parked.md`](../parked.md) P-002 pending the same
research item. If the answer comes back "hostname alone works," adding it is one line.

## Acceptance criteria

- [ ] Research Q10 answered and merged into this file before part 2 is implemented.
- [ ] Adult category toggles on and off independently of ads; disabling one does not re-download
      the other.
- [ ] `blockReason()` reports the adult source by name.
- [ ] SafeSearch rewriting verified on a real device for each listed engine, with the results and
      device recorded.
- [ ] A failed rewrite blocks rather than falls through.
- [ ] Turning the category or SafeSearch off is refused under strict lock and routed through
      `UiUtil.showStrictLockedDialog`.
- [ ] The UI states the mixed-content-platform limitation in plain language (P-001).
- [ ] Memory footprint with base + ads + adult lists measured and recorded in the PR body.
- [ ] `grep -rn "^import android" app/src/main/java/app/focusfence/dns/` returns nothing.
- [ ] `./gradlew testDebugUnitTest` passes, with `SafeSearch` mapping covered by JVM tests.

## Out of scope

- Anything in D-016's rejected list: TLS/SNI inspection, full traffic routing, on-device
  classification, content proxying.
- Per-search-term filtering or keyword blocking.
- DNS-over-HTTPS upstream. Separate concern, separate work order.
- Mixed-content platforms (P-001) and YouTube restricted mode (P-002). Both are parked, both have
  written reasoning; state the limitation, don't attempt it.
