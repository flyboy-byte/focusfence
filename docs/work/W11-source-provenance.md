# W11 — Multi-source lists with provenance and categories

**Track:** Product (B6) · **Depends on:** W9 · **Status:** ✅ **Done — 2026-08-17**, landed via
`claude/focus-fence-session-s72elg` → `shareddev`. See "How it landed" at the bottom.
**Size:** ~1 week

> **2026-08-15:** W9's core (the allowlist store and its evaluator) landed on `logandev`/
> `shareddev` — the dependency this order actually needs (an allowlist to fall back on once a
> second source exists) is satisfied. Only research Q2 (filter-list licensing) still blocks this.

## Why

"Block adult-content domains all the time" is one of the six example boundaries and is currently
**impossible**. There is one list (StevenBlack), parsed wholesale into a single `HashSet<String>`
with no category or provenance retained, behind one on/off toggle.

Flattening every source into one set destroys the information needed to answer **"why was this
blocked?"** The payoff here is explainability — *blocked because HaGeZi NSFW → Adult* rather than
just *blocked* — plus per-source enable/disable and honest license tracking.

## Do this for provenance, not for speed

`docs/audits/03-foss-ecosystem.md` analysed matching at scale and concluded the current
`HashSet<String>` is **entirely defensible at ~100k entries**. Do not turn this into a matcher
rewrite. If a benchmark (W13) later shows a real problem, that's a separate work order.

## Research Q2 — answered 2026-08-17

Full findings and citations:
[`../research/2026-08-17-filter-list-licensing-claude.md`](../research/2026-08-17-filter-list-licensing-claude.md).
What implementation must respect:

- **Source set: StevenBlack, HaGeZi, OISD, Block List Project. EasyList is dropped** — it's an
  ABP-syntax filter list, not a domain list; deriving domains from it triggers ShareAlike/GPL
  obligations on the result and yields mostly DNS-inexpressible noise. (OISD already folds that
  ecosystem's DNS-expressible content into a GPL-3.0 list.)
- **Runtime download only — never vendor a list into the repo or APK.** HaGeZi and OISD are
  GPL-3.0 (nothing owed when end-user devices fetch from the maintainer's hosting; conveying it
  ourselves would create obligations). StevenBlack's base unified list contains **non-commercial**
  upstream data (MVPS CC BY-NC-SA 4.0, Dan Pollock) — a redistribution mess the current
  architecture entirely avoids. Block List Project is public domain (Unlicense).
- **Formats: neither HaGeZi nor OISD publishes hosts format anymore.** Use HaGeZi
  `wildcard/<list>-onlydomains.txt` and OISD `https://<list>.oisd.nl/domainswild2` — both are
  bare-domain-per-line with `#` headers, which `HostsParser.parse()` already accepts unchanged
  (verified against the code). Do **not** use the `*.domain` asterisk variants — the parser's
  character filter silently skips them.
- **Record each source's license in the `FilterSource` model and show it in the provenance UI**
  (name, link, license) — that satisfies every attribution expectation in play.
- **Scale note:** OISD big alone is ~269k entries vs the ~99k W13 measured. Re-run the W13
  scenario-3 benchmark with the combined list as part of this work order (also flagged in
  `NEXT.md` and the R2 re-audit).

## Sketch

- A `FilterSource` model: id, display name, URL, category, license, enabled, last-updated,
  entry count.
- Storage keyed per source rather than one flat file. Keep `HostsParser` as-is — it parses the
  format fine; what changes is what you do with the result.
- Retain the source id alongside each entry so `blockReason()` can return *which* source matched.
  A `Map<String, String>` (host → source id) is the obvious shape; measure memory before assuming
  it's fine at 5+ lists, and consider interning source ids.
- Update scheduling per source. **No new periodic wakeup** — piggyback on existing triggers or use
  `WorkManager` with generous constraints. Battery is a product requirement.
- Categories (adult, gambling, social, ads/trackers) as user-facing toggles mapping to sources.
- Surface provenance in the UI: the stats screen and any "why was this blocked" affordance should
  name the source.
- W9's allowlist becomes load-bearing here — with five lists, false positives are certain.

## Acceptance criteria

- [x] Research Q2 answered and merged before implementation; each source's license recorded in the
      app (`FilterSource.license`/`licenseUrl`) and in the repo (`docs/research/2026-08-17-filter-list-licensing-claude.md`).
- [x] `blockReason()` can report which source matched — `_src:<id>` stats buckets, unit-tested in
      `BlocklistTest.listBlockNamesItsSource`.
- [x] Per-source enable/disable works, and disabling one does not require re-downloading others —
      one file per source; disabling only removes the id from the enabled set.
- [x] Category toggles map to sources and are expressible as a `Category` target (W8) — category
      ids are string constants in `FilterSources`, not an enum, precisely so W8 can adopt them.
- [x] No new periodic wakeup is introduced — downloads stay user-triggered only.
- [x] Memory footprint with 5 large lists measured and recorded (see below, and the commit body).
- [x] Attribution for every downloaded source is visible in-app — license label in every source
      row; tapping the row opens the license text. Nothing is bundled, deliberately (Q2).
- [x] `./gradlew testDebugUnitTest` passes — 67 tests, 0 failures (was 58 pre-W11).

## Out of scope

- Replacing `HashSet` matching with a trie or bloom filter.
- Supporting EasyList/ABP syntax — that's a different parser and a much larger project.
- Authenticated or rapidly-regenerating sources (e.g. URLhaus).

## How it landed (2026-08-17)

- **Pure layer** (`dns/`): `FilterSource`/`FilterSources` — the catalog of 8 sources across 4
  categories, every URL live-verified before being hardcoded; `Blocklist.blockReason()` now takes
  `Map<host, sourceId>` and walks parent domains (the bare-domain OISD/HaGeZi variants define an
  entry as covering its subdomains — exact-only matching would silently under-enforce them);
  `StatsCodec` gained `_src:<id>` buckets with `_ads` kept as the decoded legacy aggregate.
- **Android side**: `FilterListStore` replaces `AdListStore` — one file and enabled flag per
  source, `KEY_REV` written last as the reload signal (same pattern as before), a one-shot
  migration in `FocusFenceApp.onCreate` folds a pre-W11 install's list and enabled state onto the
  `stevenblack` source. The VPN service holds the host→source map; TTL choice keys off
  `isListBucket`.
- **UI**: Settings shows the catalog grouped by category — category switches (enable = default
  source; disable = strict-gated, per D-016) over per-source checkboxes; per-source license/count/
  updated meta; row tap opens the license. Stats gained a "Blocked by filter list today" section
  naming sources. The Home attention row generalises to any enabled-but-missing source.
- **Memory, measured** (JVM, five real lists downloaded 2026-08-17: stevenblack + oisd_small +
  hagezi_nsfw + oisd_nsfw + stevenblack_porn = 840,079 parsed entries, 756,649 after dedup):
  **72.0 MB retained heap, ~100 bytes/entry** — the strings dominate; source-id values are
  interned by construction. The default single-list config is ~13 MB, same as pre-W11. A
  worst-case all-categories config is heavy but within a modern app heap; if it ever matters on
  a real device, the compact-structure conversation belongs to P-004's discipline (benchmark
  first). The oisd_nsfw list (494k entries) is the whale — worth knowing before enabling it.
- **Still open (flagged, not blocking):** the W13 scenario-3 battery re-run with a combined list
  needs a physical device and stays queued in `NEXT.md`; an on-device sanity pass of the new
  Settings screen is worth doing before release, same as W9's.
