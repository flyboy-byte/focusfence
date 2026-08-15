# W11 — Multi-source lists with provenance and categories

**Track:** Product (B6) · **Depends on:** W9 · **Status:** Spec draft — **needs research Q2**
**Size:** ~1 week

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

## Blocked on research

Queue item 2: the actual license and redistribution terms for StevenBlack, HaGeZi, OISD, Block List
Project and EasyList — which permit runtime download vs. bundling, and what attribution each
requires.

**This is a genuine legal question, not a formality.** The app currently *downloads* rather than
*vendors* lists, which is materially safer. If this work order ever bundles a list into the repo,
the terms bind the project's own distribution. Get the answer first.

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

- [ ] Research Q2 answered and merged before implementation; each source's license recorded in the
      app and in the repo.
- [ ] `blockReason()` can report which source matched.
- [ ] Per-source enable/disable works, and disabling one does not require re-downloading others.
- [ ] Category toggles map to sources and are expressible as a `Category` target (W8).
- [ ] No new periodic wakeup is introduced.
- [ ] Memory footprint with 5 large lists measured and recorded in the PR body.
- [ ] Attribution for every bundled or downloaded source is visible in-app.
- [ ] `./gradlew testDebugUnitTest` passes.

## Out of scope

- Replacing `HashSet` matching with a trie or bloom filter.
- Supporting EasyList/ABP syntax — that's a different parser and a much larger project.
- Authenticated or rapidly-regenerating sources (e.g. URLhaus).
