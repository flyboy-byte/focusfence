# Focus Fence: Product Definition and Problem-Space Research

## Executive finding

**Product judgment:** The Focus Fence idea survives an adversarial review, but the broad version of the thesis does not.

There is **not** an empty market for “a private, open-source Android app blocker with strict mode, Shorts blocking, schedules, adult filtering, and power-user features.” That territory is already occupied surprisingly well. Mindful is FOSS and offers app limits, an “Invincible Mode,” app/internet blocking, adult-content filtering, notification controls, parental controls, and an offline/no-tracking design. DetoxDroid is FOSS and combines app disabling, usage budgets, doomscroll detection for Shorts/Reels, commitment controls, and Shizuku-related capabilities. Curbox is an especially direct competitor: it is FOSS, blocks apps and sites, blocks Shorts/Reels and URL paths, hides selected portions of app interfaces, has tamper protection and delayed settings changes, and even exposes a developer API. citeturn15view3turn15view2turn15view0turn15view1

That falsifies several early forms of the idea:

| Working hypothesis from our conversation | Research result |
|---|---|
| “FOSS + private wellbeing blocker” is the niche | **Falsified.** Mindful, DetoxDroid and Curbox already occupy it. citeturn15view3turn15view2turn15view1 |
| “Strong self-binding/strict mode” differentiates it | **Falsified.** Digital Wellbeing is deliberately easy to interrupt, but Freedom has Locked Mode, Mindful has Invincible Mode, DetoxDroid has commitment controls, and Curbox has tamper protection/settings delays. citeturn13search0turn19search2turn15view3turn15view2turn15view1 |
| “Block Shorts but retain YouTube” is unusual | **Falsified.** Curbox explicitly supports Shorts blocking, URL-path blocking, and granular YouTube UI hiding; several smaller FOSS projects specialize in the same problem. citeturn15view0turn14search6 |
| “Local/private” itself differentiates it | **Mostly falsified.** Mindful describes itself as completely offline, DetoxDroid says it has no tracking, and one sec's current Android product says intervention data stays on-device. citeturn15view3turn15view2turn19search0turn19search9 |
| “Developer support/API” would be unique | **Falsified by itself.** Curbox already has an API allowing authorized apps to start focus modes, change settings and read usage statistics. citeturn15view0turn15view1 |
| “Basic DNS ad blocking” provides a reason to exist | **Falsified as a primary identity.** AdAway, RethinkDNS, personalDNSfilter and others already specialize in that space. citeturn20search0turn20search1turn20search17 |

But the research does **not** falsify a narrower problem.

**Product judgment:** Focus Fence has a defensible identity if it becomes a **user-owned policy layer for intentional Android use**, rather than a feature-rich blocker.

The narrower problem is:

> **“I want to define nuanced boundaries for my own Android device—across apps, domains and selected content surfaces—and choose how strongly those boundaries are enforced, without surrendering privacy, device ownership, battery life, transparency, or my ability to experiment with my phone.”**

That is meaningfully different from “stop me using Instagram.”

It makes the unusual combination we have kept returning to coherent:

**custom policy + local decision-making + minimal observation + optional enforcement strength + disposable network filtering + developer ownership of the device.**

The VPN then stops being the product. Accessibility stops being the product. A blacklist stops being the product. Strict mode stops being the product. They become different means by which a user-owned policy is expressed or enforced.

**Hypothesis:** This combination appears less saturated than the broad app-blocker category. However, this research does **not** prove market demand. We have reviewed the ecosystem, platform behavior, source and behavioral literature; we have not conducted user interviews, a survey, or usage telemetry. The narrower thesis therefore deserves development consideration, not a claim that an unmet market has been quantitatively established.

## The thesis under falsification

Android itself already covers a surprisingly large part of ordinary digital wellbeing. Google's current Digital Wellbeing supports app timers, Chrome-specific site timers and Focus mode; Focus mode can be scheduled, but users can also deliberately choose “Take a break” and temporarily unpause selected apps. Android's Modes/Do Not Disturb system separately handles scheduled notification interruption. citeturn13search0turn13search4

That makes stock Digital Wellbeing very good at:

**“Help me spend less time in apps.”**

It is much weaker at:

**“I have a personal policy about exactly which parts of my digital environment I want available, under which conditions, and how difficult I want changing that policy to be.”**

That distinction matters.

A normal timer answers:

> Instagram: 30 minutes.

A richer personal policy could answer:

> Instagram messaging is useful; Explore/Reels are not.  
> Reddit is fine after work but not before noon.  
> This adult-content category is never useful to me.  
> YouTube search/direct videos are useful; Shorts and the home recommendation feed are not.  
> Ads/trackers are unwanted network noise.  
> During this work session I want TikTok unavailable.  
> I can change all of this because it is my phone—but not impulsively during a commitment period.

That is not merely “more configuration.” It changes the unit of control from **application usage** to **intentional boundaries**.

There are nevertheless formidable competitors.

Mindful currently describes app limits, grouped limits, Invincible Mode, app/internet blocking, adult-content filtering, notification management, bedtime behavior and parental controls, while saying it operates offline without tracking. Its source is GPL-2.0 licensed. citeturn15view3

DetoxDroid's F-Droid release from July 31, 2026 offers doomscroll detection for Shorts/Reels, app disabling and daily budgets, deliberate pause behavior and a commitment password. Its permission list includes Usage Stats, device-administration-related functionality, `WRITE_SECURE_SETTINGS`, overlays and the Shizuku API permission. It is GPLv3. citeturn15view2

Curbox is even closer to the broad Focus Fence vision. Its August 2026 F-Droid release describes app and website blocking, URL-path blocking such as `m.youtube.com/shorts`, short-form blocking, tamper protection, delayed weakening of settings, granular UI hiding, scheduled limits, multiple intentional-unlock mechanisms and a developer API. The GitHub project credits ShizuTools for Shizuku runner implementations and uses GPLv3-or-later. citeturn15view0turn15view1

Commercial products cover other pieces. Freedom's Locked Mode prevents changing or ending active sessions early; one sec's Android app uses Accessibility to intervene when selected apps are opened and says its intervention processing remains local. ScreenZen already offers app-specific daily limits, schedules and mindful prompts. citeturn19search2turn19search0turn19search1

And the network side is even more mature. AdAway is a FOSS Android ad blocker supporting hosts-file and local-VPN operation plus custom sources and custom allow/block rules. Rethink's Android project already spans DNS, firewall and VPN/proxifier modes, with configurable encrypted-DNS options and application/domain/IP controls. personalDNSfilter exists specifically as a DNS filtering application. citeturn20search0turn20search3turn20search1turn20search17

So a product proposition like:

> “Focus Fence is an open-source app blocker with DNS filtering, adult blocking, ads, Shorts blocking, schedules and strict mode”

would be weak. Existing FOSS projects can fairly answer, **“we already do most of that.”**

### Where the gap actually appears

**Engineering inference:** The strongest remaining gap is not individual capabilities. It is how capabilities are **composed and constrained**.

Most products understandably optimize around their primary job: Digital Wellbeing around screen-time controls, one sec around behavioral friction, Curbox around app/site/surface blocking, AdAway around advertising hosts, Rethink around networking, and PCAPdroid around network observation. Their respective capabilities overlap, but those product centers are real. citeturn13search0turn19search3turn15view0turn20search0turn20search1turn14search3

Focus Fence's opportunity is therefore not to have the longest capability list. It is to have a **consistent philosophy about when each capability is appropriate**.

One particularly important example is the Android VPN slot. Android permits only one active prepared VPN service for a user/profile; starting another replaces the current one. PCAPdroid deliberately uses `VpnService` for local packet capture and network analysis without a remote VPN. That makes the VPN slot a genuinely scarce developer resource, not just an implementation detail. citeturn13search1turn14search3turn14search7

PCAPdroid's own community has discussed the inability to simultaneously use multiple `VpnService`-based tools such as PCAPdroid and RethinkDNS. citeturn14search22

So the principle raised earlier in our conversation—**Focus Fence should remain useful after its VPN is shut off**—is more consequential than it first appeared.

It is a potential product commitment:

> Focus Fence does not assume it owns your networking stack.

That is a better power-user proposition than simply having an “off” switch.

Similarly, Android accessibility services can inspect UI content and perform actions on the user's behalf. Google's current documentation warns that listening indiscriminately to all accessibility events can be resource-intensive. citeturn13search2

The current Focus Fence source already tries to scope those observations more narrowly rather than treating Accessibility as carte blanche. That is an important philosophical differentiator **if it becomes an explicit product contract**, not merely an optimization in one implementation.

In other words:

**“We support Accessibility” is not differentiation.**

**“We expose exactly why Accessibility is required, limit what it watches to the enabled feature, and design the app so you can decline it and retain the rest of the product” potentially is.**

The same reasoning applies to VPN, shell privileges and any later privileged enforcement.

## What the evidence says about behavior change

There is enough research to justify building digital self-control tools, but nowhere near enough to justify assuming every stronger blocker improves wellbeing.

A 2023 systematic review of 55 phone-use-reduction apps found published efficacy evidence for only 13, across 19 papers. The authors judged only one of those studies methodologically strong, six moderate and eleven weak, and explicitly cautioned against overinterpreting the heterogeneous evidence. Four of the thirteen evaluated apps showed evidence of reducing phone use; results concerning maladaptive mobile-phone use were mixed. citeturn16search3

A separate systematic review and meta-analysis of digital self-control tools concluded that the category has promise, but emphasized limitations in understanding effectiveness and called for longer-term, standardized evaluation rather than treating timers and lockouts as proven solutions by default. citeturn17search0turn17search1

That supports your “actual proved worth” instinct very strongly.

### Friction has better evidence than “maximum blocking”

Some of the most interesting evidence favors **interrupting automatic behavior**, rather than merely increasing enforcement strength.

The peer-reviewed one sec study found that after six weeks the intervention was associated with a 57% reduction in actual openings of target apps. The mechanism inserted friction at the point of an otherwise automatic app-opening action. citeturn16search1turn19search5

The earlier LocknType experiment similarly tested increasing amounts of friction. Even a pause-only intervention discouraged 13.1% of opening attempts; heavier typing friction discouraged more attempts. citeturn16search2turn16search6

A 2024 randomized trial provides another useful warning against equating “wellbeing” with “blocking”: gamified goals and social competition reduced handheld-device use by 20% relative to control in that experiment. citeturn16search15

**Product judgment:** Focus Fence therefore should not adopt the philosophy:

> “The strongest possible restriction is the most effective feature.”

It should adopt:

> **“Use the minimum intervention that reliably changes the unwanted behavior the user identified.”**

That may be:

`inform → add friction → delay → time-limit → block → harden`

rather than:

`allow → absolute block`

This also makes the existing Focus Fence `StrictLock` concept more defensible. A delayed weakening of rules is behaviorally plausible because it separates the impulse from the eventual action. But **the exact current ten-minute delay is not evidence-based merely because delay/friction has evidence**. The source snapshot hard-codes ten minutes followed by a five-minute edit window. That is an implementation choice still awaiting validation.

### Nuance is probably more valuable than total abstinence

one sec's findings are especially relevant because the intervention does not assume the target application is intrinsically bad; it attempts to interrupt automatic entry and let the user make a more deliberate choice. citeturn16search1turn19search3

Curbox's current feature design independently demonstrates demand at the product-design level for fine-grained boundaries such as hiding the YouTube home feed while retaining search, or blocking a Shorts path while retaining the rest of YouTube. citeturn15view0turn15view1

This does **not** prove that every granular rule causes better outcomes.

It does make the following product hypothesis credible:

> Many people do not actually want “no YouTube,” “no Reddit,” or “no browser.” They want to remove a particular behavior or content surface while preserving the useful portion of a service.

That is where a policy model is much more interesting than a blocklist.

### Metrics should not become the product

The research review found self-tracking and goal-setting to be extremely common among evaluated tools. citeturn16search3

That does not mean Focus Fence should become an analytics application.

**Product judgment:** Local statistics should answer questions that help the user alter policy:

> “Which rule is firing?”  
> “Am I repeatedly overriding this boundary?”  
> “Did this intervention reduce unwanted openings?”  
> “How often is this category encountered?”

They should not gradually become a behavioral surveillance warehouse merely because the data can be collected locally.

This is an important distinction because **local surveillance is still surveillance capability**. Android's Accessibility API, for example, can expose screen content even though no network transmission is involved. citeturn13search2

The privacy principle should consequently be:

> **Minimize collection, not merely transmission.**

That is stronger than “your data never leaves the phone.”

## The problem Focus Fence can legitimately own

The most defensible problem statement after this research is:

> **Focus Fence lets an Android owner define and voluntarily enforce personal boundaries across apps, network destinations, and selected distracting content surfaces, using the least-observing local capability that can satisfy each boundary—without surrendering control of the device to the tool.**

I would slightly prefer **“user-owned Android policy layer”** over **“commitment firewall”** as the formal product category.

“Commitment firewall” is memorable and captures the self-binding idea, but “firewall” naturally implies network traffic. Focus Fence is already broader than networking.

A stronger conceptual description is:

> **A local policy layer for intentional Android use.**

A shorter public-facing formulation could still be:

> **A user-owned commitment firewall for Android.**

The first explains the architecture; the second communicates the feeling.

### Who it is actually for

These are **product inferences**, not market-demographic findings.

The primary user appears to be a person who says:

> “I know what I want my phone to let me do. Android's stock tools are too coarse or too easy for that particular boundary, but I refuse to install an opaque parental-control system that treats me as the child.”

A second strong audience is the Android power user/developer:

> “I want serious boundaries most of the day, but I also want to stop the VPN, launch PCAPdroid, use ADB, test another VPN, sideload software, change networking, inspect traffic, or otherwise own my device without fighting my wellbeing app.”

That audience matters because Android's one-active-VPN design creates a real conflict among local network tools. citeturn13search1turn14search7

A third audience is a person whose desired boundary is **content-specific rather than app-specific**:

> “I need YouTube, but not Shorts.”  
> “I want Instagram messaging, but not Reels.”  
> “I need a browser, but I personally don't want adult sites available.”  
> “Reddit is useful in certain contexts, but I don't want it during work.”

Curbox already validates that these are technically and product-wise plausible use cases, so Focus Fence cannot claim to invent them. citeturn15view0turn15view1

What Focus Fence could own is the **consistent policy and privacy philosophy across all of them**.

### Why use Focus Fence rather than a competitor?

Not because it has an adult list. Mindful already has adult filtering. citeturn15view3

Not because it blocks Shorts. Curbox and dedicated FOSS blockers already do. citeturn15view0turn14search6

Not because it has strict mode. Multiple competitors do. citeturn19search2turn15view3turn15view2

Not because it is FOSS. Several competitors are. citeturn15view0turn15view2turn15view3

Not because it blocks ads. AdAway and Rethink have years of specialization there. citeturn20search0turn20search1

The potential reason is the combination of four promises:

**First: policy ownership.** The user describes desired boundaries rather than simply choosing from a predetermined set of blocker features.

**Second: capability choice.** A rule should not automatically justify acquiring the strongest observation mechanism available.

**Third: composability.** Network enforcement is one optional part of the system, so stopping it does not collapse the entire product.

**Fourth: verifiability.** FOSS is used not merely as a distribution label but to make rules, data sources, capabilities and enforcement explainable to the owner.

Curbox's developer API means “developer-friendly” by itself is insufficient differentiation. citeturn15view0

**Hypothesis:** A stronger developer proposition would instead be that Focus Fence behaves like respectful infrastructure: documented configuration, predictable state, inspectable decisions, no proprietary account dependency, no assumption that it permanently owns the VPN slot, and eventually perhaps a reusable policy component.

The “eventually” is important.

A reusable engine should be a **consequence of a clean product model and a second genuine consumer**, not something constructed prematurely to make the project sound more architectural.

## Scope boundaries and coherent feature roles

The apparent grab-bag of ads, adult filtering, time limits, custom blocking and app control becomes coherent if each is understood as one of three things:

**a signal about what the user is interacting with, a policy about whether that interaction is wanted, or a means of enforcing the policy.**

That framing is stronger than organizing the app into disconnected feature categories.

Conceptually:

```text
What happened?
     │
     ▼
Observation
     │
     ▼
What does it mean?
Classification
     │
     ▼
What has the owner decided?
Policy
     │
     ▼
What is the least-invasive adequate response?
Enforcement
```

**Engineering inference:** The conceptual separation remains useful after reviewing competitors and the current source. Curbox, one sec, AdAway and Rethink show how easily a product can otherwise become defined by its enforcement mechanism—Accessibility intervention, local VPN, firewall, etc. citeturn15view0turn19search0turn20search0turn20search1

The exact software architecture is a later question. For product definition, what matters is that Focus Fence should be able to explain:

> “This was blocked because this observation matched this category/rule, and this policy is active right now.”

rather than merely:

> “Blocked.”

### Where the proposed capabilities fit

| Capability | Product role | Research judgment |
|---|---|---|
| User-defined app/domain/site rules | Expression of personal boundaries | **Core.** This is closest to the actual product promise. |
| Schedules and budgets | Policy context | **Core**, although measurement quality must be honest. |
| Delays/friction/commitment | Enforcement strength | **Core concept.** There is credible evidence that friction can alter unwanted app-opening behavior. citeturn16search1turn16search6 |
| Basic DNS domain filtering | Low-observation network enforcement | **Important optional backend**, not the identity. Android's single-VPN model means it should be disposable. citeturn13search1 |
| Adult-content domain sources | User-chosen category/intelligence source | **Fits well**, provided it remains an explicit user boundary rather than turning the product into parental monitoring. |
| Ads/trackers | Network-hygiene category | **Useful secondary feature**, but competing with mature ad blockers on breadth would be product drift. citeturn20search0turn20search1 |
| Browser path rules | More precise observation/enforcement | **Strong fit** when the user deliberately opts into it; competitors show the utility of `/shorts`-style controls. citeturn15view0turn15view1 |
| Narrow Accessibility rules | Surface-specific enforcement | **Potentially justified but privilege-sensitive.** Accessibility can inspect content and act for the user, so it should remain scoped and optional. citeturn13search2 |
| Shizuku/shell | Stronger enforcement without deeper content observation | **Interesting advanced capability**, but this Android-mechanism question belongs to the next technical research pass. DetoxDroid and Curbox demonstrate that the FOSS ecosystem already uses such approaches. citeturn15view2turn15view0 |
| Device Owner | Strong self-binding | **Experimental territory**, not normal identity. |
| Malware/phishing feeds | Defensive network category | **Peripheral.** Useful if cheap, but Focus Fence should not imply it is a security product merely because such lists are available. |
| Full packet firewall | Network product expansion | **Probably coexist rather than compete.** Rethink and PCAPdroid already specialize here. citeturn20search1turn14search3 |
| HTTPS interception | Deep content observation | **No demonstrated product need at present.** It directly challenges the minimal-observation principle. |
| Local ML content classifiers | Possible classification signal | **Research hypothesis only.** Do not add merely because a blacklist is imperfect. |
| Remote administration/reporting | Third-party control | **Contradicts the product.** |
| Child/employee monitoring | Parental/enterprise administration | **Explicit non-goal.** |
| Custom browser | Owning an entire browsing stack | **Strong non-goal absent extraordinary evidence.** |
| Cosmetic/scriptlet-grade ad blocking | Browser content blocker competition | **Non-goal.** uBlock Origin's rule model extends far beyond hostname blocking; Focus Fence should not casually recreate a browser content blocker. citeturn20search2turn20search11 |

### The blacklist question becomes much easier

Your earlier objection was exactly right:

> A blacklist is only as good as the blacklist.

The product mistake would be concluding from that:

> Therefore we need to inspect everything.

Those are not the only choices.

**Product judgment:** A domain list should be understood as one source of classification evidence.

For example:

```text
Observation:
    domain = example.tld

Classification evidence:
    User rule        → none
    Adult source A   → Adult
    Adult source B   → Adult
    Ads source       → none

Policy:
    Adult → always blocked

Enforcement:
    DNS response blocked
```

The value is no longer that “Focus Fence owns the best blacklist.”

The value is that **the owner can choose what evidence they trust and what policy those classifications invoke**.

That prevents list quality from becoming the product's identity.

AdAway already demonstrates the usefulness of user-selectable hosts sources plus custom allow/block rules, while uBlock Origin's filter-list model shows the much richer end of community-maintained filtering intelligence. citeturn20search3turn20search21

Focus Fence can learn from those ecosystems without attempting to become them.

### Adult filtering actually fits better than ad blocking

At first these two features look similar because both can be implemented partly with domain lists.

Product-wise they are different.

Ad blocking is largely a **network hygiene convenience**. There are excellent specialized tools already. citeturn20search0turn20search1

Adult filtering can represent an explicit **personal boundary**:

> “I do not want this category available to me.”

That aligns directly with the commitment-policy thesis.

So adult-content filtering can legitimately be central for a user who chooses it, without Focus Fence becoming “a porn blocker.”

The product is the rule:

```text
adult content → unavailable to me
```

The list is one classifier capable of helping enforce that rule.

### Malware and phishing deserve restraint

Blocking known malware/phishing domains is harmless enough as an optional list category when the network filter is already active. personalDNSfilter and Rethink explicitly operate in this DNS/security territory. citeturn20search17turn20search23

But calling Focus Fence a malware or phishing protection product would create a much stronger promise about threat intelligence freshness, bypass resistance, coverage and incident response.

**Product judgment:** Don't make that promise.

“Optionally consume a malware feed” and “Focus Fence protects your device from malware” are fundamentally different commitments.

## What the current Focus Fence source already tells us

The uploaded snapshot is valuable because it reveals some unusually good instincts **and** a few claims the final product should become more rigorous about.

### The DNS design already embodies least observation

**Verified from the provided source:** `DnsVpnService.kt` does not currently add a default `0.0.0.0/0` route. It assigns synthetic DNS addresses and adds host routes specifically for those DNS destinations. In practical product terms, the existing design is trying to place DNS queries through Focus Fence rather than making the app the transport path for arbitrary device traffic.

That is aligned with the product philosophy better than a conventional full-tunnel design.

Android's VPN API explicitly lets an application choose which routes enter its virtual interface, and its documentation shows that broad routes such as `0.0.0.0/0` are what cause all corresponding traffic to pass through the TUN device. citeturn13search1

**Product judgment:** Keep the principle, not necessarily the implementation:

> Do not observe traffic that is unnecessary for an enabled policy.

That should survive even if the networking code is eventually replaced.

### Accessibility is already more scoped than the scary version of Accessibility

**Verified from the provided source:** `UrlFilterService.kt` dynamically restricts its Accessibility event configuration, uses a package list rather than indiscriminately processing every application, uses a bounded fallback UI-tree search, and only enables additional content-change observation when relevant rules require it.

That matters because official Android documentation says Accessibility services can inspect UI content and act on the user's behalf, and warns that broad event subscriptions such as `typeAllMask` can be resource-intensive. citeturn13search2

The code therefore contains a product principle worth elevating:

> **Capability scope should contract when functionality is unused.**

This principle is stronger than simply saying “Accessibility processing is local.”

### The current Strict Mode is philosophically good but scientifically unproven in its details

**Verified from source:** `StrictLock.kt` uses a ten-minute delay before settings can be weakened and then opens a five-minute edit window. `DnsVpnService.kt` refuses its normal stop action while strict mode remains locked.

Research supports friction and interruption as useful behavior-change mechanisms in at least some settings. citeturn16search1turn16search6

But research does not validate this particular ten-minute/five-minute configuration.

That distinction is exactly what “actual proved worth” should mean.

Instead of:

> “Strict Mode works because it has a ten-minute delay.”

the project should eventually be able to say:

> “The general mechanism is grounded in evidence about interrupting impulsive use; our specific defaults are either experimentally justified or clearly identified as defaults rather than scientific constants.”

### DNS-derived usage time should not pretend to be exact screen time

**Verified from source:** `UsageTracker.kt` estimates domain usage by measuring gaps between qualifying DNS observations, using a three-minute session gap by default.

That is clever because it avoids deeper observation.

But it is inherently an estimate.

Stock Digital Wellbeing already provides actual app timers and Chrome site timers at the OS/product level. citeturn13search0

**Product judgment:** This is a textbook case where Focus Fence should value **privacy-preserving approximation over false precision**, while saying so explicitly.

A DNS-derived rule could reasonably mean:

> “roughly restrict repeated use of this domain”

but it should not be branded as authoritative “screen time” unless measurement validates that claim.

### The current privacy language needs to become more precise

The README says there is no external server and contains the much stronger statement that nothing about browsing leaves the phone.

**Verified from source:** allowed DNS queries are forwarded to external resolvers, currently including Cloudflare `1.1.1.1` and Google `8.8.8.8` as configured upstreams.

So literal “nothing about your browsing ever leaves your phone” is too broad.

That does **not** mean the architecture is cloud-centric. It means normal DNS resolution still involves a resolver outside the phone.

A more rigorous FOSS/privacy claim would be:

> **Policy evaluation and filtering are local. Focus Fence has no required account, policy cloud or behavioral-reporting server. Network metadata required for DNS resolution is sent to the configured DNS resolver.**

That distinction matters enormously for credibility.

A privacy-focused FOSS project should describe **what it can observe, what it stores, and what leaves the device**, rather than relying on the ambiguous adjective “local.”

### The current battery claim is not yet evidence

The README says that because only DNS traverses the app, battery and throughput impact are effectively zero.

**Verified from source inspection:** I did not find a benchmark suite, Macrobenchmark/Perfetto harness, battery experiment, or equivalent performance evidence in the uploaded snapshot supporting that magnitude of claim.

The architecture certainly gives a plausible reason to expect lower cost than a full-tunnel packet-processing VPN, but that is an engineering inference, not a measurement.

Android itself warns developers to minimize costly background behavior and unnecessary permission/service usage, and Accessibility documentation specifically notes the cost of excessive event delivery. citeturn13search2

So one of Focus Fence's strongest possible FOSS principles is:

> **“Low power” is a benchmark result, not a README adjective.**

That would actually differentiate the project's engineering culture.

### The source already contains too many ideas to let implementation define the product

The snapshot includes, among other things:

`DnsVpnService`  
`AdListStore`  
`BlocklistStore`  
`UsageStore`  
`StatsStore`  
`UrlFilterService`  
`StrictLock`  
`BlockedAppsStore`

and unit-testable logic around DNS, blocklists, URL filtering, usage and strict state.

**Engineering inference:** The danger is not a lack of capabilities. It is allowing those existing modules to imply that “the product is whatever all of these files happen to do.”

The research points the other direction:

The product definition should determine whether those capabilities remain, not vice versa.

### There are two housekeeping signals worth recording now

**Verified from the uploaded snapshot:** no `LICENSE`, `COPYING`, or `NOTICE` file was present in the project root areas inspected.

For something whose identity explicitly includes FOSS, the public project eventually needs explicit licensing terms. That is not an implementation feature; it is part of making the intended openness legally meaningful.

The snapshot also currently targets Android API 34. On **August 31, 2026**, Google Play will require new applications and updates to target Android 16/API 36 or later. That deadline is eighteen days after today's August 13, 2026 research date. This matters only if Google Play distribution is intended, but it demonstrates why Android capability assumptions should be documented by version rather than treated as permanent. citeturn13search3

## Product definition and contribution principles

The strongest product definition I can derive from the research is:

> **Focus Fence is a FOSS, local-first Android policy tool for people who want to intentionally constrain their own digital environment. It lets the device owner define boundaries across apps, domains and selected content surfaces, then choose an appropriate level of friction or enforcement. Focus Fence minimizes what it observes, does not require a remote authority, remains useful without owning Android's VPN slot, and treats the user's continued ownership of the device as a requirement rather than an obstacle.**

That definition is meaningfully narrower than:

> “digital wellbeing + ad blocker + porn blocker + app blocker + VPN.”

And meaningfully broader than:

> “DNS blocker.”

### What makes all the features one product

The unifying object should be the **boundary**, not the blocking technology.

A person might define:

```text
Boundary:
    I don't want adult content available.

Evidence:
    selected domain/category sources

Normal enforcement:
    DNS block

Fallback:
    explicit user rule

Commitment:
    cannot weaken for 10 minutes
```

Another might define:

```text
Boundary:
    YouTube is useful; Shorts are not.

Evidence:
    browser path / selected UI surface

Enforcement:
    path or UI intervention

Network:
    YouTube itself remains allowed
```

Another:

```text
Boundary:
    Reddit is fine after work.

Context:
    time / schedule

Enforcement:
    whichever enabled adapter can enforce it

VPN off:
    retain any non-network enforcement that remains available
```

And another:

```text
Boundary:
    Do not let me casually open Instagram.

Enforcement:
    pause first

Escalation:
    block after budget exhausted
```

The mechanisms differ radically.

The user's mental model does not:

> **“This is a boundary I chose.”**

That is how ads, adult filtering, app limits and strict mode can coexist without becoming an arbitrary collection.

### Contribution principles strong enough to reject features

Future contributors need principles capable of saying **no** to impressive code.

I would make these the product-level acceptance test:

| Principle | Question a proposed feature must answer |
|---|---|
| **User-owned** | Does this help the device owner enforce their own intention, or does it move us toward administrator-over-user control? |
| **Problem before mechanism** | What concrete unwanted behavior cannot be addressed acceptably by existing capabilities? |
| **Least observation** | Can the same outcome be achieved without collecting or inspecting additional information? |
| **Proportional enforcement** | Does the requested boundary actually require this strength of control? |
| **Capability optionality** | Can users decline this privileged mechanism without unnecessarily losing unrelated features? |
| **Composability** | Does this unnecessarily monopolize a scarce facility such as `VpnService`, or can Focus Fence coexist with specialist tools? Android's one-active-VPN rule makes this particularly important. citeturn13search1 |
| **Measured cost** | What battery, CPU, RAM, wakeup, latency or reliability cost does this impose, and has it been measured? |
| **Explainability** | Can the application tell the owner why something was blocked and which source/policy produced the decision? |
| **Reversibility** | Can a knowledgeable owner deliberately regain control without uninstall/recovery gymnastics unless they explicitly chose an exceptional commitment mode? |
| **FOSS usefulness** | Is the implementation understandable, documented and reusable enough that openness provides practical value rather than simply source availability? |
| **Evidence of benefit** | Is there behavioral evidence, user evidence, or a convincing prototype showing that this solves something people actually encounter? |
| **Specialist test** | Is this a core Focus Fence problem, or are AdAway, Rethink, PCAPdroid, uBlock Origin, Digital Wellbeing or another specialist already the better tool? citeturn20search0turn20search1turn14search3turn20search2turn13search0 |

That test would reject several technically fascinating directions right now.

### Explicit non-goals

**Product judgment:** Focus Fence should not be a parental surveillance suite, enterprise MDM, accountability-reporting platform, remote administration service, general-purpose VPN provider, replacement for PCAPdroid, complete Android firewall, uBlock-Origin-equivalent browser engine, malware-security suite, custom browser, or TLS-interception product.

That does not mean code supporting one of those areas can never teach Focus Fence anything.

It means **those are not problems Focus Fence promises to solve**.

HTTPS interception is the clearest example. No evidence in this product pass establishes a user problem important enough to justify making TLS decryption part of Focus Fence's normal identity. PCAPdroid already demonstrates that local TLS decryption is a specialist network-analysis capability; Android Accessibility demonstrates that seemingly “local” capabilities can still expose highly sensitive content. citeturn14search3turn13search2

The burden of proof should therefore be reversed:

> Don't ask, “What could HTTPS inspection let us block?”

Ask:

> **“Which important, validated Focus Fence boundary is impossible to enforce acceptably without decrypting the user's traffic?”**

Until a convincing answer exists, it stays out.

The same applies to machine-learning classifiers, full TUN packet engines and Device Owner hardening.

### Ad blocking should remain deliberately boring

AdAway already gives Android users a mature FOSS hosts/VPN blocker with custom sources and rules. Rethink goes far beyond that into DNS/firewall/VPN operation. uBlock Origin supports a far richer filtering language than DNS can provide, including EasyList-compatible and extended filter concepts. citeturn20search3turn20search1turn20search2

So Focus Fence should not have an internal cultural incentive to say:

> “Our ad blocker must become as good as uBlock.”

A better promise is:

> **“Because a low-level domain policy mechanism exists, you may optionally use it for basic ad/tracker filtering too.”**

That feature earns its place because it is low incremental complexity and useful to many users—not because Focus Fence wants ownership of the ad-blocking problem.

### Likewise, do not make “unbreakable” the goal

Stock Digital Wellbeing deliberately provides a “Take a break” escape hatch. citeturn13search0

Freedom moves farther toward irreversible sessions with Locked Mode. citeturn19search2

Curbox, Mindful and DetoxDroid each provide their own stronger-control mechanisms. citeturn15view0turn15view3turn15view2

The research on friction suggests a better Focus Fence objective: introduce enough resistance to convert an impulsive act into a deliberate one, rather than escalating indefinitely toward adversarial lockout. citeturn16search1turn16search6

A sophisticated user deliberately opening ADB to work on their phone is categorically different from the same user tapping “disable blocker” five seconds after encountering a distraction.

Focus Fence should be designed to understand that difference philosophically even if Android cannot perfectly distinguish it technically.

> **Impulsive bypass is the adversary. The device owner is not.**

That may be the single most important sentence in the eventual product brief.

### The developer identity needs to be substantive

Curbox already has an explicit developer API, so merely publishing an intent/API does not establish a unique developer identity. citeturn15view0

For Focus Fence, “built for developers” should instead mean:

The VPN is disposable because Android developers may need the single VPN slot for PCAPdroid, Rethink, WireGuard or their own code. Android explicitly allows only one active prepared VPN service per user/profile. citeturn13search1turn14search7

Configuration should be inspectable and portable rather than trapped in opaque app state.

Blocking should be explainable.

Privileged integrations should be optional.

The application should expose what capabilities are active and what each can observe.

Experiments should be easy to enable and equally easy to remove.

No Focus Fence account should be necessary to recover one's own configuration.

A later engine/library should become possible because policy logic is genuinely useful independently—not because “having an SDK” sounds mature.

And measurements should be reproducible enough that a contributor can challenge claims such as battery performance.

That is a much stronger FOSS identity than merely putting the source on GitHub.

### Final falsification result

The broad thesis—

> **“There should be a private FOSS Android blocker combining ads, adult filtering, app limits, Shorts blocking and anti-bypass.”**

—is not strong enough. Several active open-source projects already invalidate its supposed novelty. citeturn15view0turn15view2turn15view3

The narrower thesis—

> **“There should be a user-owned Android policy system that combines nuanced self-imposed boundaries with explicitly chosen enforcement strength, minimizes observation, remains useful when individual capabilities such as VPN are disabled, respects developers' ownership of the device, and requires expensive mechanisms to demonstrate measurable value.”**

—survives this research.

It also explains the current source better than “DNS filter” does.

The existing DNS-only routing shows the beginnings of minimal observation. The scoped Accessibility implementation shows capability minimization. StrictLock represents voluntary self-binding. Custom domain rules represent explicit policy. The ad list represents external classification intelligence. The approximate DNS usage mechanism represents a deliberate trade of precision for shallower observation.

Those pieces do not need to become a giant filter stack.

They can instead become manifestations of one product principle:

> **Let me decide the boundaries of my own device, help me honor them, observe no more than necessary to do so, and never forget that the phone is still mine.**