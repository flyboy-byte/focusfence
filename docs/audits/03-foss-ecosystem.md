# Focus Fence and the FOSS Filtering Ecosystem

## Executive findings

The strongest conclusion from this research is that **Focus Fence should not try to win by having the biggest blacklist, the most capable packet engine, or the most features**. Mature FOSS projects already cover each of those territories extremely well. uBlock Origin is a sophisticated browser-content filtering engine; Rethink DNS + Firewall + VPN is already a full Android DNS/firewall/proxy stack; NetGuard is a mature no-root application firewall; AdAway handles hosts-based and VPN-based ad blocking; personalDNSfilter specializes in local DNS filtering; PCAPdroid is an unusually capable local traffic-analysis environment. More directly, newer FOSS wellbeing applications such as Mindful now combine screen-time limits, app blocking, Internet blocking, adult filtering, focus modes and tamper-resistant “invincible” modes. citeturn22search2turn19search3turn17search4turn22search0turn17search3turn18search0turn26search0

That means **“FOSS wellbeing app with DNS blocking, adult filtering and hard-to-disable rules” is not, by itself, a sufficient differentiator anymore**. Mindful in particular is a useful falsification of that thesis: its current public description already includes daily screen-time limits, blocking distracting apps, cutting off Internet access, adult-content filtering, offline operation, privacy-first design and an “Invincible Mode.” Curbox is another actively developed GPLv3 FOSS app/website blocker oriented around screen addiction and usage insight. citeturn26search0turn26search3

Where Focus Fence **can** become unusually interesting is in combining those concerns around a different architectural and philosophical center:

> **A user-owned, local policy engine for self-imposed digital boundaries, with interchangeable observation and enforcement mechanisms.**

That distinction matters. In that model, DNS filtering, ad blocking, adult-domain feeds, schedules, Accessibility rules, package controls and optional privileged enforcement are **inputs and actuators**, not the product definition. A developer can turn the VPN off and retain app/time policy. A normal user can enable only DNS. A power user can supply their own lists. A future developer could consume the policy engine without consuming Focus Fence's Android UI.

The current source is already unusually compatible with that direction. The uploaded snapshot separates several pieces into Android-independent Kotlin logic: `Blocklist`, `BlockRule`, `HostsParser`, `UsageTracker`, `StrictLock`, `UrlFilter` and related tests. `DnsVpnService` is already DNS-only rather than a full packet proxy, and the VPN routes only the application's synthetic DNS addresses rather than all device traffic (`DnsVpnService.kt:42–49, 180–193`). That resembles the historical architecture of DNS66, whose VPN likewise routed traffic for DNS servers into itself rather than implementing a general-purpose packet tunnel. citeturn17search0

There are, however, two claims in the present source that should be treated as hypotheses rather than facts. First, the README and `DnsVpnService` describe the battery impact as essentially or effectively zero. The narrow DNS-only architecture is certainly much cheaper in *work performed* than forwarding every TCP/UDP flow, but I found no Focus Fence benchmark establishing “effectively zero,” and rigorous comparable battery measurements are surprisingly scarce in the upstream FOSS documentation. That is precisely the sort of claim your “actual proved worth” principle should require the project to measure rather than assume.

Second, `AdListStore.kt:16–18` describes StevenBlack as being aggregated from “the same upstream sources AdGuard/uBlock use.” That is too broad. StevenBlack is explicitly an aggregator of several hosts sources and currently reports a 97,646-entry base list, while uBlock Origin's defaults include EasyList, EasyPrivacy, Peter Lowe's list, an online malicious-URL list and uBO-maintained lists and support a much richer filtering syntax than hosts files. There can be source overlap in the broader ecosystem, but these are not equivalent pipelines. citeturn16search1turn22search2

My overall research judgment is therefore:

**Keep the current DNS approach simple until measurements or a concrete policy demonstrate its insufficiency. Invest first in making filtering sources, classifications, policy and enforcement cleanly separable.**

That gives Focus Fence something the larger networking applications do not necessarily optimize for: **composability without compulsory infrastructure**.

## Current source and ecosystem fit

The uploaded source is much closer to a sound minimal architecture than it initially appears.

`AdListStore` currently has exactly one external source, the StevenBlack unified hosts file, downloaded into private storage and parsed wholesale into memory (`AdListStore.kt:14–24, 41–47, 58–84`). `HostsParser` accepts either traditional hosts syntax or one-domain-per-line input and places normalized domains in a Java/Kotlin `HashSet<String>` (`HostsParser.kt:5–8, 18–42`). `Blocklist` separately represents first-party user rules, including schedules, time budgets and browser path exceptions (`Blocklist.kt:5–25`).

That distinction is important: **the app already has the beginnings of “user policy” versus “external intelligence,” even though the implementation currently calls one side `AdListStore`.**

The matching approach is also reasonable for the current scale. For a queried host, Focus Fence can perform exact hash lookup and walk its parent domains rather than enumerating every subdomain in a feed. In practical terms, a lookup for `a.b.example.com` can test successively broader suffixes (`Blocklist.kt:156–168`). With about 100,000 imported domains, this is exactly the kind of boring implementation that should be preferred until profiling says otherwise.

The problem appears when the source model grows from:

> StevenBlack → one giant `Set<String>`

into something like:

> HaGeZi adult + malware + tracking  
> Block List Project phishing  
> user's custom work-distraction list  
> social category  
> local overrides  
> custom Git source  
> maybe several million total observations

Flattening all of those into one `Set<String>` destroys information that Focus Fence will increasingly care about:

```text
Why was this blocked?
Which source classified it?
Which category does it belong to?
Did the user override the source?
Do two sources disagree?
Is it ads, adult, malware, social, or explicitly user-blocked?
Should this policy apply all day or only during work?
```

That is the strongest argument for evolving beyond `AdListStore`; **performance is not yet the strongest argument**.

The ecosystem reinforces that conclusion. AdAway lets users configure their own hosts sources. Rethink exposes configurable DNS deny/allow behavior and separates its Android application from a lower-level networking library. AdGuard maintains a registry of host lists with metadata and acceptance criteria. HaGeZi publishes distinct categories and multiple blocking-strength tiers rather than pretending one list suits every user. The Block List Project likewise explicitly emphasizes category-specific control and multiple output formats. citeturn22search0turn19search3turn16search7turn21view0turn21view1

So a generic filter-source abstraction is not speculative architecture for architecture's sake. It is a pattern repeatedly validated by mature filtering projects.

A useful source concept would eventually need to preserve, conceptually, information such as:

```text
Source
  identity
  provenance
  category/categories
  license
  format
  upstream location
  update metadata
  enabled state
  priority/trust
  compiled rule set
```

The user-visible result is more important than those implementation details: Focus Fence could say **“blocked because HaGeZi NSFW → Adult policy”** instead of merely **“blocked.”**

That explainability fits the project's FOSS identity far better than silently constructing a giant opaque blacklist.

There is another important ecosystem result: **Focus Fence should deliberately coexist with specialized tools rather than absorb them.** PCAPdroid already handles local packet capture, SNI/DNS/HTTP inspection, PCAP export and optional TLS decryption far more extensively than Focus Fence needs to. PCAPdroid's no-root mode itself uses Android's VPN mechanism, and all traffic processing is local. citeturn18search0turn18search6 Rather than implementing a second-rate network analyzer, Focus Fence should make it painless to surrender the VPN slot so a developer can run PCAPdroid.

Similarly, uBlock Origin is better positioned to perform browser cosmetic filtering, scriptlet injection, resource-specific filtering and anti-annoyance rules because it actually lives in the browser execution context. Its filters use EasyList syntax plus uBO extensions, and hosts files are merely one supported source type. citeturn22search2 Focus Fence has little to gain from recreating browser cosmetic filtering at the Android network layer.

This produces a useful boundary:

**Focus Fence should own policy. Specialized tools can own deep network analysis and browser-content rewriting.**

## FOSS project audit

The projects fall into noticeably different architectural families, and treating all of them as “ad blockers” obscures the useful lessons.

| Project | Current state and architecture | Rules / relevant capability | License | Best lesson for Focus Fence |
|---|---|---|---|---|
| **uBlock Origin** | Current, mature browser content blocker. Supports Firefox, including Android Firefox, and uses EasyList syntax plus extensions. citeturn22search2 | Network filters, URL/resource context, cosmetic filters, richer browser rules, hosts files and uBO-specific syntax. citeturn22search2 | GPLv3. citeturn22search2 | Study rule semantics, precedence, exceptions and diagnostics. **Do not turn Focus Fence into uBO outside the browser.** |
| **AdAway** | Hosts-based Android blocker with both hosts-file and local-VPN operation; custom hosts sources are supported. Maintainers describe the project as actively developed, although the most recent stable release surfaced by GitHub is substantially older than the freshest projects in this comparison. citeturn22search0turn16search4 | Domain/hosts filtering, user-selectable sources; root and non-root deployment models. citeturn22search0 | GPLv3+. citeturn22search0 | Study source management and root/non-root separation. Don't inherit the whole product. |
| **Rethink DNS + Firewall + VPN** | Very active; rich local VPN/firewall/DNS/proxy application with DoH, DoT, DNSCrypt, WireGuard, connection tracking and per-app/domain/IP controls. citeturn19search3 | DNS lists, app firewall policy, IP/domain rules, upstream DNS protocols, full traffic routing. citeturn19search3 | Android app Apache-2.0. citeturn19search0turn19search3 | Probably the most valuable Android architecture reference. **Do not compete with its entire networking stack.** |
| **Firestack** | Rethink's separately published Android network engine: userspace TCP/UDP monitor, firewall, DNS resolver and WireGuard stack, built around gVisor/netstack and a hard fork of Outline's tun2socks. Published as an AAR, but APIs are explicitly unstable/undocumented. citeturn19search1 | Full TUN TCP/UDP handling; DoH, DoT, ODoH, DNSCrypt, WireGuard and proxy plumbing. citeturn19search1 | MPL-2.0. citeturn19search1 | Strong candidate to **evaluate later** rather than build a home-grown TCP/IP stack if Focus Fence ever proves it needs full-TUN processing. |
| **personalDNSfilter** | Current DNS-focused FOSS project; GitHub showed a release about two months before this research. citeturn17search3 | Local DNS filtering and filter-list handling. | Repository identified as GPL-2.0; exact file headers should be checked before assuming whether reuse is `GPL-2.0-only` or `-or-later`. citeturn17search3 | Closest maintained specialist reference for keeping DNS filtering narrow. |
| **DNS66** | Historical DNS-only Android hosts blocker. Its architecture diverts DNS-server traffic through a `VpnService` and forwards non-blacklisted DNS queries. citeturn17search0 | Hosts/domain blocking with multiple lists and list ordering. citeturn17search0 | GPLv3. citeturn17search0 | **Very relevant historically because this is essentially the same minimal VPN philosophy as current Focus Fence.** Study; do not resurrect wholesale. |
| **NetGuard** | Current mature no-root Android firewall. Uses `VpnService`; applications and addresses can be individually allowed or denied. It explicitly notes that Android VPN services cannot be chained. citeturn17search4turn17search1 | Per-app network policy, address rules, optional hosts-based blocking and traffic logging. citeturn17search4 | GPLv3. citeturn17search4 | Excellent lifecycle, VPN-compatibility and app-policy reference. Too broad to fork for Focus Fence. |
| **PCAPdroid** | Active local packet monitor; v1.9.1 was released February 22, 2026. No-root capture uses a local VPN and can extract DNS, SNI, HTTP URLs and connection metadata. citeturn18search0turn18search3turn18search6 | Packet/connection analysis, PCAP export, protocol decoding; separate MITM addon supports TLS decryption via mitmproxy. citeturn18search0turn17search2 | GPLv3. citeturn17search2turn17search5 | **Coexist with it.** Use it to diagnose Focus Fence rather than absorb its functionality. |
| **AdGuard DnsLibs** | Current cross-platform C++ DNS proxy/filter library with an Android adapter. Supports caching, filtering, DNS redirection, DoH, DoT, DoQ and DNSCrypt. citeturn22search1 | A real reusable DNS engine rather than an Android application. | Apache-2.0. citeturn22search1 | **Strongest direct library candidate** discovered for a future richer DNS backend, subject to native/JNI complexity proving worthwhile. |
| **AdGuard host-list tooling** | AdGuard maintains DNS filter syntax, host-list compilation tooling and a registry of lists. The registry itself requires quality, maintenance and false-positive handling from candidate lists. citeturn16search7 | List validation, metadata, format compilation, DNS-appropriate subset of adblock syntax. | Several relevant AdGuard repositories are GPLv3; DnsLibs is separately Apache-2.0. citeturn16search7turn22search1 | Excellent reference for **source governance** and compiled-list tooling even if code is not copied. |
| **Mindful** | Current FOSS Android digital-wellbeing application covering focus sessions, screen-time limits, distracting-app blocking, Internet blocking, adult filtering, offline privacy and a harder-to-bypass “Invincible Mode.” citeturn26search0 | Wellbeing/app/Internet policy rather than primarily a filtering engine. | GPL-2.0 according to its repository metadata. citeturn26search0 | Crucial competitive reference: proves Focus Fence cannot differentiate merely by combining wellbeing + blocking + FOSS. |
| **Curbox** | Actively developed FOSS app/site blocker focused on digital habits and usage control. citeturn26search3 | App/site blocking and usage-oriented features. | GPLv3. citeturn26search3 | Another reason to define Focus Fence around **policy openness/composability**, not just “open-source blocker.” |

There is a useful architectural spectrum here:

```text
Browser filtering
uBlock Origin
        |
        | rich HTTP/browser context
        v
DNS filtering
DNS66 / personalDNSfilter / current Focus Fence
        |
        | more traffic visibility and more cost/complexity
        v
Full local firewall / TUN
NetGuard / Rethink
        |
        | protocol analysis
        v
PCAPdroid
        |
        | explicit decryption
        v
PCAPdroid MITM / similar HTTPS proxying
```

Focus Fence does **not** need to move downward on that diagram merely to become more capable.

A more important second axis is:

```text
Policy / self-control
Mindful / Curbox / Focus Fence
```

That leads to a critical product insight from this research:

**Focus Fence's interesting opportunity is to cross the two axes without collapsing them into each other.**

A policy such as “social media blocked until 5 PM” should not care whether the enforcement happens via DNS, app suspension, Accessibility or some future mechanism. Conversely, the DNS engine should not contain assumptions about whether a domain is distracting, adult, malicious or merely user-disallowed.

That is exactly where an internal reusable engine begins to make sense.

The battery evidence also argues against automatically borrowing the largest network stack. These projects document features and architecture much more often than controlled energy benchmarks. NetGuard, Rethink and PCAPdroid prove that sophisticated no-root networking is feasible, but they do **not** establish that Focus Fence would gain enough value from full-TUN operation to compensate for the additional packet processing, state tracking, native code and lifecycle surface. citeturn17search4turn19search3turn18search0

Therefore “Rethink can do it” or “Firestack can do it” is evidence of feasibility—not evidence that Focus Fence should do it.

## Filter data ecosystem

This is where your original statement—**“a blacklist is only as good as the blacklist”**—holds up extremely well.

No single public list has a credible claim to being the definitive categorization source. The maintained ecosystem instead consists of raw sources, curators, aggregators, category-specific lists, conservative lists and aggressive lists with different false-positive philosophies.

### StevenBlack

StevenBlack is a good baseline because it is simple and currently very manageable. The repository describes itself explicitly as an aggregator and reported **97,646 entries on August 11, 2026**. It also exposes optional extensions for categories such as porn, social media and gambling. The repository itself is MIT-licensed. citeturn16search1turn16search5

That makes it appropriate for Focus Fence's current “basic ads/tracking/security” toggle, particularly because the existing parser consumes its native hosts syntax directly.

It should **not**, however, become the conceptual foundation for categories. A merged hosts file has intentionally thrown away much of the semantics of its upstream sources. StevenBlack's own repository lists upstream projects with their own licenses and purposes, so Focus Fence should not assume that the aggregate's repository-level MIT license magically resolves every upstream provenance question for arbitrary redistribution strategies. citeturn16search1

### EasyList and EasyPrivacy

EasyList and EasyPrivacy are much richer than a hosts blacklist. EasyList-family rules can express request patterns, contexts and other semantics that require a browser or filter engine rather than a DNS set; current EasyList files demonstrate resource-oriented rules and contextual modifiers rather than simply domain names. citeturn22search3turn16search14

They are also highly active: EasyList's repository showed substantial July 2026 commit activity, and an EasyList-family generated subscription inspected during this research was stamped August 13, 2026. citeturn16search10turn22search3

The repository contents are dual-licensed under **GPLv3-or-later or CC BY-SA 3.0-or-later unless otherwise noted**, while externally hosted subscriptions referenced by the repository can have separate conditions. citeturn24view0

For Focus Fence, the conclusion is:

**Do not feed arbitrary EasyList syntax into a DNS parser.**

If future DNS filtering supports a richer syntax, define a deliberately supported DNS subset and reject or ignore rules whose semantics cannot be preserved. AdGuard's DNS tooling follows this general principle: its DNS filtering syntax is a DNS-appropriate subset rather than pretending browser rules have identical meaning at the resolver. citeturn22search1

Full EasyList support would make much more sense in a future browser-specific adapter than in the core DNS matcher.

### HaGeZi

HaGeZi is probably the most interesting category source for Focus Fence to evaluate.

As of this research, HaGeZi publishes multiple general protection tiers—Light through Ultimate—rather than one arbitrary severity level. Its current general lists range from roughly 41,000 entries in Light to roughly 269,000 in Ultimate. It separately publishes threat intelligence, DNS-bypass lists, gambling, social-network, NSFW and native-tracker categories. citeturn21view0

The category sizes illustrate why Focus Fence should not flatten everything together. HaGeZi currently reports approximately:

- **430,948** gambling entries,
- **898** social-network entries,
- **110,339** NSFW entries. citeturn21view0

It publishes these in several formats, including adblock-style, DNSMasq, wildcard-domain, plain-domain and RPZ variants depending on the category. citeturn21view0

The repository is GPLv3. citeturn21view0

This is almost a demonstration of the source model Focus Fence wants: **category and aggressiveness are first-class information**.

A user might reasonably configure:

```text
Ads/tracking:
    HaGeZi Light

Adult:
    HaGeZi NSFW

Gambling:
    disabled

Social:
    use my own rules instead
```

That is much more expressive than one `adblock_enabled` Boolean.

### OISD

OISD represents a different philosophy. Its public site explicitly says the main list prioritizes **functionality over maximal blocking**, and its primary list targets ads, app ads, phishing, malvertising, malware, spyware, ransomware, cryptojacking and tracking/analytics while deliberately not blocking categories such as porn, social networks or gambling in the normal list. Those are separated; it publishes dedicated NSFW lists. citeturn21view2

Its update page was showing updates within minutes during this research, indicating a highly dynamic build process. citeturn21view2

OISD also provides a useful technical lesson: it discontinued traditional hosts/domain syntax because enumerating known subdomains is inefficient compared with wildcard-capable or ABP-like domain semantics. Its own explanation says wildcard-capable representations can cover unknown/random subdomains with a single parent-domain rule. citeturn21view2

Interestingly, **Focus Fence's current parent-domain matching already obtains much of that advantage internally**, even though its source is hosts-format. If the set contains `example.com`, a query for `random.example.com` can match the parent without requiring every random hostname to appear in the downloaded list. So Focus Fence should not blindly reproduce OISD's source-format conclusion; its matcher is already more expressive than a literal `/etc/hosts` implementation.

OISD is nevertheless valuable for comparing false-positive philosophy against more aggressive aggregates.

### Block List Project

The Block List Project is perhaps the strongest candidate for **granular user-selectable categories**. Its current repository describes 18 main lists and four beta lists, including ads, malware, phishing, tracking and gambling, and exposes multiple generated formats. It also documents automated upstream monitoring, dead-domain checks, duplicate detection, DNS/HTTP validation and safeguards for critical domains. New additions are described as daily. citeturn21view1

The repository uses the **Unlicense**. citeturn21view1

Its 2026 repository tree also contains service-specific lists such as TikTok, Twitter, WhatsApp and YouTube, which could be interesting for experimentation, though a service-domain list should not be confused with semantic sub-feature filtering such as “YouTube Shorts only.” citeturn21view1

That distinction is important:

```text
youtube.com / googlevideo etc.
        ≠
YouTube Shorts
```

A DNS source can classify infrastructure associated with YouTube. It cannot inherently understand the product/UI state “the user is watching Shorts.”

### URLhaus and live security intelligence

URLhaus is qualitatively different from the static wellbeing lists. It publishes current malware URLs and derived DNS datasets. Its database dumps are regenerated every five minutes; its RPZ feed contains hostnames associated with active malware URLs or entries added in the preceding 48 hours, and it excludes hostnames belonging to Tranco's top million to reduce false positives. citeturn21view3

This makes it technically attractive as a security source, but it is **not a good candidate to silently bundle as another static ad list**. Its current downloads require authentication, and a live five-minute threat feed has a fundamentally different lifecycle from a user downloading an adult-content list once a week. citeturn21view3

That is another argument for source metadata:

```text
StevenBlack:
    slow-changing convenience aggregate

HaGeZi NSFW:
    content category

Block List Project phishing:
    category/security

URLhaus:
    ephemeral threat intelligence
```

Those should not all share one refresh policy.

### What combining lists really buys you

The evidence does **not** support “more lists = better.”

Large aggregators already ingest many of the same upstream projects. OISD explicitly publishes its included-source inventory, which demonstrates how rapidly one aggregate can overlap with EasyList, AdGuard lists, security feeds and smaller community sources. citeturn20search6turn20search7 HaGeZi likewise describes its lists as compiled and optimized from various sources rather than raw independent measurements. citeturn21view0 StevenBlack is explicitly an aggregator. citeturn16search1

Adding three aggregates can therefore produce:

```text
more memory
+ more update bandwidth
+ more provenance complexity
+ more false-positive surface
+ surprisingly little unique coverage
```

rather than three times the protection.

For Focus Fence, the better strategy is **diversity by purpose**, not volume:

```text
one conservative ad/tracker baseline
+
one adult category source
+
one live security source if desired
+
small purpose-built social/wellbeing categories
+
user rules
+
local exceptions
```

The generic source model should preserve enough provenance to measure later whether source B actually adds useful unique domains over source A.

That would allow Focus Fence to eventually show something genuinely informative:

```text
HaGeZi NSFW
110,339 source rules
94,210 unique after compilation

Block List Project Adult
83,xxx source rules
7,xxx additional unique classifications

User overrides
14 allowed
27 explicitly blocked
```

The numbers above are illustrative except for HaGeZi's current source count; the point is that **coverage contribution should become measurable**, not assumed.

## Matching, storage, and the reusable engine

The current `HashSet<String>` is not a problem that needs immediate replacement.

At roughly 100,000 domains, it gives Focus Fence several things that matter:

- conceptually trivial lookup;
- very low algorithmic complexity;
- simple unit testing;
- easy parent-domain matching;
- inexpensive list rebuilds;
- minimal new native dependencies.

The hidden cost is JVM object overhead: millions of individually allocated strings and hash-table entries become substantially more expensive in RAM than the textual domain data itself. The precise cost depends on ART/JDK implementation, string representation, load factor and device, so assigning a universal “X MB per million domains” number would be pseudo-precision. **Benchmark it on actual target Android devices.**

A sensible scale interpretation is:

| Approximate scale | Best default research position |
|---|---|
| **~100k rules** | Current `HashSet<String>` approach is entirely defensible. Optimize only after measurement. |
| **~1m rules** | Begin testing compact representations against `HashSet`: sorted binary domain table, reversed-domain radix/trie, possibly a disk-backed source-of-truth with compiled memory index. |
| **~5m rules** | Millions of ordinary Kotlin `String` objects should no longer be assumed acceptable. A compiled compact structure becomes a serious design candidate. |

A **sorted canonical domain table** deserves more respect than it usually gets. Normalize domains, store them compactly, sort them, and use binary search. Lookup becomes `O(log n)` instead of expected `O(1)`, but the representation can be dramatically simpler and more memory-map friendly than millions of heap objects. Updates can rebuild a file transactionally. For a DNS engine where lookups are measured in dozens or hundreds per minute rather than billions per second, the constant factors may be completely acceptable.

A **reversed-domain trie/radix tree** maps particularly naturally onto DNS policy:

```text
com
└── google
    ├── ads
    └── youtube
```

instead of storing unrelated strings:

```text
ads.google.com
youtube.google.com
```

Reversal groups domain suffixes, which is exactly what Focus Fence's current parent-domain loop is trying to reason about. A terminal node could carry a compact category/source mask:

```text
example.com
  categories = ADULT | TRACKING
  sources    = HAGEZI | USER_CUSTOM
```

That preserves explainability without storing duplicate domain strings per source.

This technique has real large-scale precedent. Rethink's serverless DNS/blocklist tooling compiles very large collections into a **succinct radix trie**, with its repository documenting datasets in the many-millions-of-entry range. That is server-side rather than an Android performance result, so it proves the compact-trie approach is practical at scale, not that its exact implementation should be transplanted into Focus Fence. citeturn14search0turn14search1

A **Bloom filter** is useful only when there is something expensive behind it.

For example:

```text
DNS query
   |
Bloom says definitely absent
   |                \
  yes                maybe
   |                  |
allow quickly      disk/trie lookup
```

Its primary property is that a negative can be definitive while a positive can be false. Therefore it must never independently cause a block.

If the entire 100k set is already an in-memory `HashSet`, putting a Bloom filter in front may simply make every query perform additional hashing before the already-fast hash lookup. At multi-million scale with a disk-backed index, the tradeoff becomes more interesting.

**SQLite** is attractive as the canonical metadata database rather than necessarily the hot path. It handles transactional updates and can naturally represent:

```text
domain
source
category
enabled
provenance
override
```

Then a compact lookup representation can be generated from it. That gives you durable, inspectable data without forcing every DNS query through a complex relational lookup.

A **DAWG/FST** or similarly compressed automaton can achieve excellent sharing across millions of strings, but the implementation complexity is substantially higher. There is no evidence in the current Focus Fence workload justifying that complexity today.

A **compiled ABP engine** has the same conditional answer. AdGuard's DnsLibs is already a cross-platform DNS filtering library with caching, filtering and modern encrypted upstream support and an Android adapter. citeturn22search1 If Focus Fence eventually needs rich DNS filter syntax, DnsLibs deserves serious evaluation before implementing a new ABP parser/matcher.

But there is no reason to adopt a full ABP engine merely so that Focus Fence can consume:

```text
example.com
ads.example.net
```

The richer engine earns its complexity only when users need richer semantics.

### The engine concept survives the research

The proposed:

```text
Observation
    ↓
Classification
    ↓
Policy
    ↓
Enforcement
```

model is one of the strongest ideas from the earlier conversation.

It is not exactly how every FOSS project names its layers, but mature systems repeatedly separate equivalent responsibilities. AdGuard publishes DnsLibs as a reusable DNS engine with platform-specific adapters rather than embedding its DNS implementation entirely inside one Android UI. Rethink publishes Firestack separately from its Android application. uBlock Origin separates rich filter parsing/matching semantics from user-configurable list selection and browser integration. citeturn22search1turn19search1turn22search2

For Focus Fence, those layers could conceptually mean:

```text
Observation
-----------
DNS query: reddit.com
Browser URL: youtube.com/shorts/...
Foreground package: com.zhiliaoapp.musically
Current time: 14:32

Classification
--------------
reddit.com → Social / Reddit
youtube.com/shorts → YouTube / Shorts
package → TikTok
unknown.xyz → no classification

Policy
------
Social blocked during work
Shorts always blocked
TikTok 20 minutes/day
Unknown content allowed

Enforcement
-----------
DNS NXDOMAIN
Accessibility navigation
app bounce
future package suspension
allow
```

The crucial architectural property is:

> **The policy should not need to know how a DNS packet is encoded, and the DNS service should not decide what the user's life goals are.**

That also answers whether Accessibility and Shizuku belong inside the engine: **they should not**.

Those are Android enforcement adapters.

Likewise:

```text
VpnService        Android adapter
Accessibility     Android adapter
Shizuku           Android adapter
Device Owner      Android adapter
Notifications     Android UI/platform
```

while concepts such as:

```text
domain
URL
application identity
classification
schedule
budget
decision
reason
override
commitment state
```

can potentially remain pure policy-domain concepts.

That would make the engine useful even while the VPN is stopped—the exact property you said matters for developers.

It also enables rigorous tests:

```text
Given:
  social category
  weekday 14:00
  work profile active

Expect:
  BLOCK
  reason = WORK_SCHEDULE
```

without creating a fake VPN or AccessibilityService.

The current code already points in this direction because `Blocklist` explicitly describes itself as pure logic free of Android types (`Blocklist.kt:75–76`), and several policy-adjacent pieces already have JVM unit tests.

I would, however, distinguish an **internal reusable engine architecture** from a **published reusable library**.

The first is justified now.

The second has not yet earned its maintenance cost.

A public library creates API-compatibility obligations. Until Focus Fence either has a second consumer or its policy boundaries stop changing rapidly, “could become reusable” is a better requirement than “must immediately ship a general-purpose SDK.”

That is a good example of your “proved worth” principle applied to architecture itself.

## Licensing and evidence-backed shortlist

The first licensing finding is immediate and concrete: **the uploaded Focus Fence snapshot contains no `LICENSE`, `COPYING` or equivalent top-level license file.**

For a project intended to have a genuine FOSS identity and accept outside contributions, that needs to be resolved before substantial upstream code is copied. Until a license is actually granted, public source code is not automatically reusable by others merely because they can see it.

Code licensing and **filter-data licensing should also be treated as separate problems**. Focus Fence can be GPL/MIT/MPL/Apache while a downloaded list is under GPL, Creative Commons, Unlicense or source-specific terms. EasyList explicitly warns that externally hosted subscriptions referenced by its repository can carry their own conditions. citeturn24view0

That argues for storing license/provenance information per filter source rather than pretending there is one license for “the blacklist.”

### What the main project-license choices mean

**MIT** maximizes downstream freedom. It permits use, modification, sublicensing and distribution provided the copyright and permission notice is retained. citeturn27view2 That is excellent if the central goal is “other developers should be able to embed the Focus Fence engine anywhere,” but it also permits proprietary forks to take improvements private.

**Apache-2.0** is similarly permissive but includes an explicit contributor patent license and more structured redistribution/NOTICE requirements. citeturn25search1 Rethink's Android application and AdGuard DnsLibs demonstrate that sophisticated FOSS networking projects can successfully use Apache-2.0. citeturn19search0turn22search1 For a reusable developer-facing engine, Apache is particularly attractive if broad adoption and patent clarity matter.

**MPL-2.0** is a middle ground. Mozilla describes it explicitly as **file-level copyleft**: modifications to MPL-covered files remain subject to MPL source-sharing obligations, while those files can form part of a larger work containing differently licensed code. citeturn25search0turn25search2 This is philosophically attractive for an engine where you want improvements to the engine itself to remain open without requiring every application embedding the engine to adopt the same license. Firestack is a relevant real-world example of an Android networking library using MPL-2.0. citeturn19search1

**GPLv3** is the strongest practical fit if the project's priority becomes keeping distributed derivatives of the application under reciprocal open-source terms and directly incorporating GPLv3 code from this ecosystem. uBlock Origin, DNS66, NetGuard, PCAPdroid and significant AdGuard list tooling are GPLv3-family projects. citeturn22search2turn17search0turn17search4turn17search5turn16search7 GPLv3's redistribution provisions require license/source obligations for covered distributed work; exact questions about whether a particular form of linking or copying creates one covered combined work are legal questions, so “GPL library means one simple mechanical rule” is an unsafe way to manage compliance. citeturn28search3

**AGPL** would chiefly become strategically relevant if Focus Fence later introduced a remotely operated filtering/policy service and wanted reciprocal-source obligations to cover network use. Given the present philosophy—no required account, no mandatory remote server, local-first operation—it adds little obvious value to the Android product today while increasing licensing friction. That is a product judgment, not a claim that AGPL is inappropriate in general.

The biggest practical licensing consequence is therefore not “pick GPL because FOSS” or “pick MIT because developers.” It is:

> **Choose what freedom you want downstream developers to have before borrowing substantial code, because that choice determines which shortcuts remain available.**

A permissive/MPL Focus Fence can comfortably evaluate Apache-2.0 components such as AdGuard DnsLibs or Rethink application architecture, but directly transplanting GPL application code creates a different licensing problem. A GPLv3 Focus Fence has much easier conceptual alignment with the enormous GPL filtering ecosystem but becomes less attractive as an engine for developers who want to embed it in differently licensed applications.

There is also an especially important warning around personalDNSfilter and Mindful: their repositories are labeled GPL-2.0, while several other candidates are GPLv3 or Apache-2.0. **Do not assume all “GPL” labels are interchangeable.** The distinction between `GPL-2.0-only` and `GPL-2.0-or-later` can materially affect license compatibility; inspect the actual relevant file headers and license notices before code reuse. citeturn17search3turn26search0

This is not legal advice; for an eventual public release that directly incorporates code from multiple copyleft projects, a license audit is worthwhile.

The research also reveals that **direct code reuse is probably less important than expected**. Focus Fence's interesting part—the policy model—is specific enough that copying somebody else's giant application may save less work than it creates. The best directly reusable external component discovered is probably AdGuard DnsLibs, and even there the C++/JNI integration cost needs to be weighed against the remarkable simplicity of the current Kotlin DNS implementation. citeturn22search1

The evidence-backed final groups are therefore:

**Projects/components worth studying.** Rethink is the strongest full Android architecture reference, especially the separation between its application and Firestack. NetGuard is valuable for VPN lifecycle, per-app policy and the reality of VPN coexistence constraints. personalDNSfilter and DNS66 are the best narrow DNS references, with DNS66 particularly relevant because its DNS-routing architecture closely resembles current Focus Fence. uBlock Origin is the standard worth studying for rule semantics, exceptions, precedence and diagnostics rather than for Android networking. AdGuard's host-list registry/compiler concepts are excellent models for source metadata and source-quality governance. Mindful and Curbox should be studied as **product competitors**, because they test whether Focus Fence's wellbeing features are genuinely distinctive. citeturn19search3turn19search1turn17search4turn17search3turn17search0turn22search2turn16search7turn26search0turn26search3

**Libraries/code potentially worth reusing.** AdGuard DnsLibs is the standout: Apache-2.0, cross-platform, Android-supported, and already implements caching, DNS filtering, encrypted DNS protocols and redirection. It is worth evaluating if Focus Fence later needs more sophisticated DNS resolution than its present small Kotlin implementation. Rethink Firestack is a plausible later candidate if a demonstrated use case requires full TCP/UDP TUN handling; its MPL-2.0 licensing is comparatively integration-friendly, but its own maintainers state that its API is unstable and designed specifically around Rethink, so it should not be adopted casually. MIT/Apache utility pieces discovered in upstream dependency trees may also be reusable individually, but none justify importing a whole application architecture. citeturn22search1turn19search1turn25search0

**Projects that should not be forked wholesale.** uBlock Origin solves the wrong platform problem: its power comes from browser context Focus Fence does not possess. AdAway would make Focus Fence inherit an ad-blocker product model. NetGuard and Rethink would turn a lightweight wellbeing/policy application into a general network firewall/VPN product. PCAPdroid would be especially counterproductive to fork because one of Focus Fence's stated design goals is allowing users to stop its VPN and use PCAPdroid itself. DNS66 is historically useful but is a simpler, older version of territory Focus Fence already occupies. Mindful and Curbox should be competitive references, not codebases Focus Fence absorbs; otherwise it risks becoming a clone of an existing FOSS wellbeing tool instead of discovering its own architectural value. citeturn22search2turn22search0turn17search4turn19search3turn18search0turn17search0turn26search0turn26search3

**Filtering/data sources worth evaluating.** Keep StevenBlack as a conservative simple baseline while correcting its provenance description. Evaluate HaGeZi strongly for explicit adult, gambling, social, threat and tracking categories and its different aggressiveness tiers. Evaluate Block List Project for granular categories and relatively clean licensing/automation. Compare OISD as a deliberately functionality-preserving aggregate rather than simply stacking it on top of every other aggregate. EasyList/EasyPrivacy should be considered only where Focus Fence implements semantics capable of honoring their richer rules—never as arbitrary text fed into the current hosts parser. URLhaus is compelling for opt-in live malware protection, but because it is a rapidly refreshed authenticated threat feed, it should be treated as a separate security-intelligence class with its current usage terms reviewed before integration or redistribution. citeturn16search1turn21view0turn21view1turn21view2turn24view0turn21view3

The most defensible architectural conclusion from all of this is not “replace Focus Fence's current filtering stack.” It is almost the opposite:

```text
                 FOCUS FENCE CORE

          Observation
              │
              ▼
        Classification
              │
              ▼
            Policy
              │
              ▼
           Decision
              │
       ┌──────┼────────┬───────────┐
       ▼      ▼        ▼           ▼
      DNS   Browser    App      Privileged
      VPN   adapter   adapter     adapter
```

The external ecosystem can then plug into the left and bottom edges:

```text
StevenBlack ─────┐
HaGeZi ──────────┤
OISD ────────────┤
BLP ─────────────┼──> Classification
user lists ──────┤
local overrides ─┘

DnsVpnService ─────────> Enforcement
Accessibility ─────────> Enforcement
future Shizuku ────────> Enforcement
future Device Owner ───> Enforcement

PCAPdroid ─────────────> coexist / development tool
uBlock Origin ─────────> complementary browser tool
```

That is materially different from making Focus Fence a thinner Rethink, a mobile uBlock clone, a more complicated AdAway, or an open-source parental-control suite.

And crucially, it gives the project a way to apply the standard you have repeatedly emphasized: **a capability earns inclusion when it demonstrates value against its privacy, battery, complexity and interoperability cost.**

At the current scale, `HashSet<String>` probably passes that test. A generic source/category model passes it because the existing single-list design already throws away information Focus Fence will need. A policy/enforcement separation passes it because VPN-off operation and future enforcement mechanisms are explicit product requirements. AdGuard DnsLibs is worth an experiment only after the existing DNS backend hits a demonstrated limitation. Firestack/full TUN is further away. A giant ABP engine is conditional. HTTPS interception has not come close to demonstrating enough additional value to justify becoming part of the architectural center.

That is a considerably stronger foundation for the eventual Claude handoff than “here are the best filtering repos to fork.”