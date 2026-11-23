# Q2 — Filter-list licensing (StevenBlack, HaGeZi, OISD, Block List Project, EasyList)

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items", not via a browser deep-research session)
**Date:** 2026-08-17
**Feeds:** W11 (go/no-go), `docs/risks.md` §Legal, D-016/W14 (category-list sourcing)
**Sourcing method:** primary sources only — LICENSE files fetched raw from each project's own
repository or official site, list headers fetched from the live list endpoints, plus each
project's own FAQ/readme statements. Every fetch below was performed live on 2026-08-17. No claim
below rests on a blog post or on model priors.
**Not legal advice** — same caveat `docs/risks.md` already carries. This is careful reading of
license text, not a lawyer's opinion.

---

## Exact prompt used

```
I'm building an open-source Android app (Focus Fence) that downloads third-party DNS
hosts-file blocklists at runtime and merges them into a local ad/tracker filter. It does
not bundle or redistribute the lists in its source repo or its release APK — it fetches
them over HTTPS from the maintainer's own hosting at runtime, on-device, and nothing is
re-hosted or re-distributed by the app's developers.

I need the actual license and redistribution terms for these five hosts-list projects, as
they exist today:
1. StevenBlack/hosts
2. HaGeZi's DNS blocklists
3. OISD
4. Block List Project (blocklistproject/Lists)
5. EasyList (used as an ad-block format, not hosts format — note if it's structurally
   different enough that this comparison doesn't apply)

For each: what is the actual license (cite the LICENSE file or repo page), does it
distinguish "runtime download by an end-user's device" from "bundling/redistribution in
another project," what attribution (if any) is required and where would it need to appear
in an app that never embeds the list, and is there a specific clause about commercial use
or forks I should know about.

This feeds a real go/no-go decision for a feature (multi-source blocklists, "W11") that
currently doesn't exist yet — so getting this wrong either blocks a real feature
unnecessarily or ships a license violation. Cite the actual license text/URL for every
claim, not a general impression of "these are usually permissive." If any of the five has
ambiguous or no explicit license, say so plainly rather than assuming permission.
```

---

## Headline answer

**W11 is a GO under the current runtime-download architecture, with one source dropped and one
source's fine print understood:**

- **HaGeZi (GPL-3.0), OISD (GPL-3.0), Block List Project (Unlicense/public domain)** are
  unambiguous for runtime download and would even permit bundling with modest obligations.
- **StevenBlack** is MIT *as a compilation*, but the unified list carries upstream data under
  **non-commercial** terms (MVPS: CC BY-NC-SA 4.0; Dan Pollock: "non-commercial with
  attribution"). Fine for what the app does today (an end user's device downloads it for
  personal use); **do not vendor the unified list into the repo or APK** without dropping those
  sources. The **porn extension is cleaner than the base list** — all its sources are MIT or
  CC BY 4.0.
- **EasyList should be excluded from W11** — its own licence terms are workable (dual
  GPLv3 / CC BY-SA 3.0) but it is structurally the wrong object: an Adblock-Plus-syntax filter
  list whose value (element hiding, URL patterns) is invisible at DNS level, and converting it
  to a domain list is an adaptation that triggers ShareAlike/GPL obligations on the result while
  producing mostly garbage. W11's existing "no ABP parser" scope line already points this way.
- **Surprise finding that matters more than the licenses:** neither HaGeZi nor OISD publishes a
  hosts-format file at all anymore. Both publish bare-domain lists (HaGeZi
  `wildcard/*-onlydomains.txt`, OISD `.../domainswild2`) — which `HostsParser.parse()` already
  accepts unchanged (verified against the code and live list samples, below).

## Per-project findings

### 1. StevenBlack/hosts — MIT compilation over mixed-license sources

- **Repo/compilation license: MIT.** `license.txt` at repo root, © Steven Black.
  Source: https://raw.githubusercontent.com/StevenBlack/hosts/master/license.txt (fetched
  2026-08-17).
- **The data is not uniformly MIT.** The readme's own source table (readme.md, "Sources of hosts
  data unified in this variant") lists per-source licenses:
  MIT (Steven Black's own list, add.2o7Net/Dead/Risk/Spam, Badd Boyz, hostsVN, UncheckyAds),
  CC BY 3.0 (AdAway), CC BY-SA 4.0 (KADhosts), CC0 (minecraft-hosts, URLHaus),
  CC BY 4.0 (Tiuxo), **CC BY-NC-SA 4.0 (MVPS)**, **"non-commercial with attribution"
  (Dan Pollock / someonewhocares.org)**, and **no license stated** for yoyo.org.
  Source: https://raw.githubusercontent.com/StevenBlack/hosts/master/readme.md (fetched
  2026-08-17).
- **Runtime download vs bundling: no explicit statement anywhere** — the distinction comes from
  how copyright works, not from the project's terms. An end user's device fetching the list for
  personal use sits comfortably inside even the non-commercial upstream terms; the app's
  developers never copy or distribute anything. **Bundling the unified list into the repo or APK
  would make the project a redistributor of MVPS/Pollock data under non-commercial ShareAlike
  terms** — a mess across 16 sources that runtime download entirely avoids.
- **The porn extension (W14's interest) is cleaner than the base list.** The porn-only variant's
  source table lists exactly six sources: Sinfonietta (MIT, two lists), pornhosts (MIT),
  BigDargon hostsVN-adult (MIT), Tiuxo pornography (CC BY 4.0), brijrajparmar27 (CC BY 4.0).
  No non-commercial terms. Source:
  https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/readme.md
  (fetched 2026-08-17).
- **Attribution:** MIT requires notice preservation only when redistributing; the app doesn't
  redistribute. The CC BY sources' attribution expectation is satisfied by W11's provenance UI
  (source name + link + license), which is the feature's whole point anyway.

### 2. HaGeZi DNS blocklists — GPL-3.0, and no hosts format anymore

- **License: GPL-3.0.** `LICENSE` at repo root is the full GPLv3 text. Source:
  https://raw.githubusercontent.com/hagezi/dns-blocklists/main/LICENSE (fetched 2026-08-17).
- **The project's own words** (FAQ §9 "Licensing and liability", fetched 2026-08-17):
  *"The lists are published under the GPL-3.0 license and can be redistributed, modified, or
  adapted, but only within the terms of that license. Review the license in the repository
  before redistributing the lists as part of your own product or service."* The readme carries
  the same warning. Source: https://raw.githubusercontent.com/hagezi/dns-blocklists/main/FAQ.md.
- **Runtime download: unconditionally fine.** GPL obligations attach to *conveying*; an app whose
  end users' devices fetch the list from HaGeZi's own hosting conveys nothing. GPLv3 §2 places
  no conditions on running or on propagation that isn't conveyance.
- **Bundling: possible but pointless.** Shipping the list inside the APK would be conveying it;
  as a data file alongside an MIT app this is plausibly GPLv3 §5's "aggregate" (the app itself
  is not infected), but the project would owe the list's license text and source availability.
  Runtime download keeps the obligation count at zero — stay there.
- **Formats — a premise correction.** HaGeZi publishes each list in exactly five formats:
  Adblock, DNSMasq, Wildcard (Asterisk), Wildcard (Domains only), RPZ (FAQ §3). **There is no
  hosts format** — `hosts/nsfw.txt` and similar paths return HTTP 404 (probed 2026-08-17).
  The consumable variant is `wildcard/<list>-onlydomains.txt` — e.g.
  https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt
  (`# Syntax: Domains (without subdomains)`, 112,390 entries on 2026-08-17, `#`-comment header,
  bare domain per line, subdomains implied).
- **Attribution:** GPL requires none in the UI; every list's header embeds `# License:` and
  `# Homepage:` lines — keep them visible in provenance.

### 3. OISD — GPL-3.0, ABP-or-domains formats only, and it is itself an aggregator

- **License: GPL-3.0**, stated three independent ways: the FAQ ("Under which license are the
  oisd lists distributed? GNU General Public License v3.0", https://oisd.nl/faq, fetched
  2026-08-17); the `License:` header in every served list pointing at
  https://github.com/sjhgvr/oisd/blob/main/LICENSE; and that LICENSE file itself (full GPLv3
  text, fetched raw 2026-08-17). Maintainer: Stephan van Ruth (list header).
- **Same runtime-download analysis as HaGeZi** — unconditionally fine; don't bundle, nothing owed.
- **OISD is an aggregator** (its "included lists" page enumerates upstream sources), asserting
  GPLv3 over the compilation. Whether every upstream grant supports that is OISD's problem as
  publisher, not the consuming app's — noted for honesty, not as a blocker.
- **No hosts format, confirmed from two directions.** The FAQ has a whole section titled *"Why is
  oisd no longer providing the oisd blocklists in domains and hosts formats?"*, and the per-tool
  setup page for AdAway states *"AdAway is not known to support a current oisd blocklist
  format."* (both fetched 2026-08-17). Live formats verified by fetching headers:
  - `https://big.oisd.nl` — `Syntax: Adblock Plus Filter List`, 269,111 entries (2026-08-17)
  - `https://big.oisd.nl/domainswild` — `*.domain` per line
  - `https://big.oisd.nl/domainswild2` — **`Syntax: Domains (wildcards) without *.` — bare
    domain per line**, `#` header comments, subdomains implied ("Entry: example.com should block
    access to example.com and subdomain.example.com").
  An `oisd nsfw` list exists in the same format family (FAQ) — relevant to W14.

### 4. Block List Project — The Unlicense (public domain)

- **License: The Unlicense.** `LICENSE` at the repo root: *"This is free and unencumbered
  software released into the public domain. Anyone is free to copy, modify, publish, use,
  compile, sell, or…"* Source:
  https://raw.githubusercontent.com/blocklistproject/Lists/master/LICENSE (fetched 2026-08-17).
- **No attribution required, no commercial restriction, no runtime-vs-bundling distinction to
  worry about** — the most permissive of the five by a distance. Show provenance in the UI
  anyway (W11's feature, and basic honesty), but nothing is owed.

### 5. EasyList — dual GPLv3 / CC BY-SA 3.0, and structurally the wrong object

- **License, verbatim from the project's licence page** (https://easylist.to/pages/licence.html,
  fetched 2026-08-17): *"Unless otherwise noted, the contents of the EasyList repository
  (https://github.com/easylist) is dual licensed under the GNU General Public License version 3
  of the License, or (at your option) any later version, and Creative Commons
  Attribution-ShareAlike 3.0 Unported, or (at your option) any later version. … if required,
  'The EasyList authors (https://easylist.to/)' should be attributed as the source of the
  material."* The same page warns that *externally hosted* subscriptions referenced by the repo
  may be under other conditions.
- **The structural answer the prompt asked for: yes, different enough that the comparison doesn't
  apply.** EasyList is Adblock-Plus filter syntax — element-hiding rules and URL patterns with
  options — not a domain list. Most of its content cannot be expressed at DNS level at all, and
  deriving a domain list from it is an *adaptation* under CC BY-SA (ShareAlike obligations on
  the derived list) or a modified work under GPL, for a result that would be mostly noise at
  DNS granularity. **Recommendation: EasyList is out of W11's source set**, which agrees with
  W11's existing out-of-scope line ("Supporting EasyList/ABP syntax — that's a different parser
  and a much larger project"). Anyone wanting EasyList-family coverage at DNS level should use
  OISD, which already folds that ecosystem's DNS-expressible content into a GPL-3.0 list.

## Verified against this codebase (per research-handoff.md step 5)

The format finding was checked against the actual parser, not assumed:
`HostsParser.parse()` (`app/src/main/java/app/focusfence/dns/HostsParser.kt`) strips
`#`-comments, then accepts either `0.0.0.0/127.0.0.1/::/::1 host` lines **or single-token
bare-domain lines** (`parts.size == 1` branch). Both HaGeZi `*-onlydomains.txt` and OISD
`domainswild2` are exactly that shape (samples fetched and eyeballed 2026-08-17) — **they parse
with zero code changes**. The `*.domain` asterisk variants would be silently skipped by the
character filter (`*` fails `isLetterOrDigit`), so W11 must use the bare-domain variants, not
the asterisk ones. Subdomain semantics ("entry blocks its subdomains") match `Blocklist.matches`
exactly.

## Consequences for the docs that consume this

1. **W11 unblocks.** Source set: StevenBlack (runtime-download only — never vendor; NC upstreams),
   HaGeZi (GPL-3.0), OISD (GPL-3.0), Block List Project (Unlicense). EasyList dropped. Each
   source's license recorded in the `FilterSource` model per the acceptance criteria.
2. **D-016 premise correction (small):** it says HaGeZi NSFW and StevenBlack porn are
   "hosts-format files that drop straight into the existing matcher." HaGeZi's is now a
   domains-format file — which *still* drops straight into the existing matcher (verified
   above), so the conclusion survives; only the format name is stale. StevenBlack's porn
   alternates remain genuine hosts format.
3. **List sizes are about to jump.** OISD big alone is 269,111 entries versus the 98,950 the W13
   benchmark measured. The R2 re-audit's follow-up (re-run scenario 3 with the combined W11
   list) gets more load-bearing, not less.
4. **`docs/risks.md`** §Legal's "the terms have not actually been read" is no longer true —
   updated to cite this file.

## New questions surfaced

- None requiring a new research item. The one judgment call a human might want to revisit
  someday: whether StevenBlack's base unified list is worth keeping as the default ads source
  long-term versus OISD big / HaGeZi Multi (single-license GPL sources with no NC upstream
  baggage). Current architecture (runtime download) makes this a preference, not a compliance
  issue — no action needed.
