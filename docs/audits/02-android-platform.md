# Focus Fence on Android: What the Platform Actually Allows

## Executive finding

The strongest conclusion from this research is that the **linear privilege ladder we had been discussing is the wrong mental model**.

Focus Fence should not think in terms of:

> normal → VPN → Accessibility → shell → Device Owner → packet inspection → HTTPS inspection

as though each step were simply a more powerful version of the previous one.

Android gives you at least **two largely independent axes**:

| Axis | Weak end | Strong end |
|---|---|---|
| **Observation** | local settings / time | UsageStats → DNS → scoped UI → all packets → decrypted content |
| **Enforcement** | reminder / friction | DNS refusal → UI bounce → package suspension → Device Owner restrictions |

That distinction is technically real. A Device Owner can suspend an application so thoroughly that it cannot launch activities, show notifications, appear in recents, display dialogs, play audio, or vibrate, while Focus Fence does not need to inspect the app's content at all. Conversely, a local TLS interception engine could potentially observe sensitive plaintext while still being relatively easy for the device owner to switch off. citeturn17search2turn19search3

**Product judgment:** this validates one of the most important instincts from our conversation: **Focus Fence should optimize for strong enforcement with shallow observation.** “More powerful” should ordinarily mean better ability to uphold a rule, not greater ability to inspect what the user is doing.

The Android platform offers a surprisingly good combination for that philosophy:

**Local policy + optional UsageStats + optional DNS VPN + narrowly scoped Accessibility + optional Shizuku** covers a large amount of the intended product without TLS interception, root, or full-device MDM. Device Owner adds genuinely stronger enforcement, but its provisioning model is so consequential that it belongs in an experimental/dedicated-device category rather than normal Focus Fence operation. Full-TUN processing has some legitimate experimental value before HTTPS interception, but TLS MITM itself currently fails the project's “proved worth versus cost” test. citeturn20search2turn18search0turn13search0turn15search0turn17search6turn19search3

There is also a favorable finding from the uploaded source snapshot: **the current architecture is already less invasive than the words “Android VPN filter” make it sound.** `DnsVpnService.kt` adds only /32 and /128 routes for Focus Fence's synthetic IPv4 and IPv6 DNS addresses rather than default routes for all traffic. Android's VPN API defines routes as the destinations that are sent through the TUN interface, so the existing service is genuinely scoped to those DNS destinations rather than secretly reading every network packet. Its upstream DNS sockets are also passed through `VpnService.protect()`, avoiding a VPN-routing loop. Android permits precisely this selective-route construction. citeturn18search0turn20search3

That has one unavoidable cost: **even a tiny DNS-only `VpnService` still owns Android's VPN slot.** Android permits only one active VPN service per user/profile; starting another stops the existing one. PCAPdroid likewise uses a local `VpnService` for its no-root capture mode. So your earlier requirement was exactly right: the Focus Fence network component must remain disposable, and the rest of the application should remain useful when PCAPdroid or another VPN owns the slot. citeturn18search0turn20search0

My overall verdict as of **August 13, 2026** is therefore:

> **Focus Fence does not need to become a miniature CloudVeil or Android firewall in order to become substantially more capable.**
>
> The most promising technical identity is a **local policy and commitment engine with interchangeable, least-invasive enforcement adapters**.

The VPN can be one adapter. Accessibility can be another. Shizuku can be another. Device Owner can be an intentionally unusual experimental deployment. None of them needs to define the application.

## What the current Focus Fence source already gets right

I inspected the uploaded `focus-fence.zip` rather than treating our earlier descriptions of it as authoritative. Several design decisions are stronger than I expected.

The current build targets API 34 with a minimum API of 26. Its manifest declares the Android 14-era `FOREGROUND_SERVICE_SPECIAL_USE` permission and marks the VPN service `specialUse`; Android 14 and later require foreground services to declare an appropriate service type and corresponding permission. Structurally, that is the correct direction for a long-running custom VPN service, though eventual store distribution still needs a fresh policy/target-SDK audit. citeturn13search2turn14search11

The DNS architecture in `DnsVpnService.kt` is deliberately narrow:

```text
TUN address
   ↓
synthetic DNS address only
   ↓
/32 IPv4 route + /128 IPv6 route
   ↓
Focus Fence examines UDP/53 DNS
   ↓
blocked → local response
allowed → protected upstream socket
```

It does **not** install `0.0.0.0/0` or `::/0` and therefore does not intentionally route general web/video/app traffic through its userspace process. Android's `VpnService.Builder` explicitly allows selected routes, DNS servers, and allowed/disallowed application sets. citeturn18search0turn20search3

That is an excellent property to preserve. **Inference:** because the TUN reader handles only traffic routed to those DNS addresses rather than every TCP/UDP flow, this should require dramatically less packet-processing work than a full-TUN firewall. But the repository's stronger battery assertions should still be considered hypotheses rather than facts until measured. Android provides Batterystats, Perfetto, Power Profiler and Macrobenchmark `PowerMetric` specifically because power cost is difficult to infer accurately from source inspection alone. citeturn14search1turn14search2turn14search26

The source also does something very good in `UrlFilterService.kt`: the Accessibility service dynamically restricts `packageNames` to applications it actually needs to observe. Browser `TYPE_WINDOW_CONTENT_CHANGED` events are enabled only while path rules exist, it batches event delivery, throttles URL checks, bounds its fallback tree search to 250 nodes, and listens to blocked apps plus supported browsers rather than continuously crawling arbitrary app UIs. Android explicitly supports restricting accessibility events to specific packages and requires `canRetrieveWindowContent=true` when the service intends to inspect a UI hierarchy. citeturn13search0

That is exactly how the project's privacy principle should manifest technically: **make broad permissions behave narrowly even when Android grants the service broad potential capability.**

There is nevertheless an important distinction between *what the current implementation does* and *what Accessibility permission makes possible*. Android describes `canRetrieveWindowContent` as permission to inspect the UI hierarchy, including screen text. Google Play therefore treats non-accessibility-tool use as sensitive: such apps require an Accessibility declaration, prominent disclosure and affirmative consent, and Play says narrower APIs should be used wherever practical. citeturn13search0turn13search7

The current whole-app blocker is also intentionally simple: when an event from a blocked package appears, it calls `GLOBAL_ACTION_HOME`. Section blocking finds a browser address-bar node and calls `GLOBAL_ACTION_BACK` when the local URL policy rejects it. That is **enforcement by UI reaction**, not OS-level package blocking. This matters when discussing what “strict” means.

The present `StrictLock` is also philosophically sounder than trying to pretend a normal APK is an administrator. It creates a ten-minute unlock delay followed by a five-minute edit window and refuses relaxing changes through Focus Fence's own interfaces while locked. But a user who deliberately enters Android Settings can still revoke the VPN, turn Accessibility off, clear data, or uninstall the app. Android explicitly calls `VpnService.onRevoke()` when VPN authorization is removed; moreover, Google Play expressly forbids Accessibility from being used to prevent users from disabling or uninstalling apps/services except in parental-control or enterprise-management contexts. citeturn18search3turn13search3

That means **the current Strict Mode is a commitment/friction mechanism, not a security boundary**. I think that is a feature, not a defect.

The weakest architectural assumption I found concerns time measurement. `UsageTracker.kt` infers website use from DNS activity: queries close enough together are treated as an active session. That is clever and costs essentially no additional observation because the DNS service already has those events, but DNS requests are not equivalent to user attention. DNS caches can suppress lookups while the user remains active, background work can generate requests while the user is not actively consuming content, and a site's activity can involve many ancillary domains. Android already exposes package foreground/background activity through `UsageStatsManager` and `UsageEvents`, including `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED`, behind `PACKAGE_USAGE_STATS`. citeturn20search2turn12search4

**Product judgment:** keep DNS-inferred time if it is useful for **domain-specific approximate activity**, but do not eventually present it as authoritative “screen time.” For app-level budgets, UsageStats is a much better low-observation source and, critically, it works when the VPN is completely off. It exposes package/activity timing, not browsing content. citeturn20search2turn12search4

That is a particularly good fit for your developer requirement:

```text
PCAPdroid owns VpnService
        │
        ├── Focus Fence schedules still work
        ├── app budgets can still work
        ├── local policy still works
        ├── Accessibility rules can still work
        └── shell / Device Owner enforcement can still work

Focus Fence DNS/ad/domain filtering:
        temporarily unavailable
```

Android's one-VPN restriction makes that modular separation practically necessary rather than merely architecturally pretty. citeturn18search0turn20search0

## Android capability map

The following is the practical capability matrix I would use going forward. “Observation” describes information that Focus Fence can potentially see. “Enforcement” describes how strongly it can uphold a user rule. Battery rankings in this table are **engineering expectations, not measured Focus Fence results**.

| Mechanism | Observation potential | Enforcement potential | Non-root? | Reboot behavior | Works with FF VPN off? | Expected runtime cost | Focus Fence fit |
|---|---|---|---|---|---|---|---|
| Local policy/state | Essentially none | Friction inside FF | Yes | Persistent state | **Yes** | Minimal | **Core** |
| UsageStats | Package/activity/time | None by itself | Yes | Access normally persists | **Yes** | Low if queried sanely | **Core optional signal** |
| DNS-only VpnService | DNS names/timing handled by VPN | Strong domain refusal | Yes | Can use always-on | N/A | Low relative to full TUN; must measure | **Normal optional** |
| Scoped Accessibility | Selected app UI, browser URL/text exposed in hierarchy | Back/Home/gesture-level action | Yes | System-managed enabled service | **Yes** | Depends heavily on event scope | **Optional targeted adapter** |
| Shizuku/ADB shell | Potentially broad shell capabilities; intended use can remain package-only | Package suspend/disable and other shell actions | Yes with Shizuku ADB mode | **Shizuku must be restarted after reboot** | **Yes** | Unknown; benchmark | **Advanced/power-user** |
| Device Owner/DPC | Can remain shallow if only policy APIs used | Very strong OS-enforced restrictions | Yes | Persistent | **Yes** | Much enforcement happens in system | **Experimental/dedicated-device** |
| Full-TUN VpnService | IP/port/timing, cleartext protocols, encrypted-flow metadata | Connection/IP/protocol firewall | Yes | Same VPN lifecycle | No | Materially higher than DNS-only expected | **Experimental** |
| HTTPS MITM | Potential plaintext URLs, headers and content | Fine-grained content/request filtering where compatible | Technically possible only with substantial caveats | CA/proxy state persists separately | Usually requires network backend | Highest privacy/complexity burden | **Research-only / presently reject** |

The platform documentation supports the key differences in that table. `VpnService` creates a TUN interface from which the application reads outgoing IP packets and writes incoming packets; only one can be active per user/profile. Accessibility can inspect a window hierarchy when configured to retrieve it. UsageStats exposes app-usage events rather than content. Shizuku in ADB mode runs with shell identity and explicitly notes that ADB permissions vary across Android releases and are substantially different from root. A Device Owner can selectively suspend installed packages. citeturn18search0turn13search0turn20search2turn15search1turn17search2

The implication is important: **Device Owner is actually less observationally invasive than full packet capture if all you use it for is package suspension.** The setup is far more administratively invasive, but that is a different type of cost.

### Ordinary app and UsageStats capabilities

A normal app can store policy, evaluate schedules, provide friction, maintain local statistics and observe its own lifecycle. With special Usage Access, `UsageStatsManager` can query foreground/background events and aggregated usage for other packages; `ACTIVITY_RESUMED` records an activity moving to the foreground and `ACTIVITY_PAUSED` records it moving to the background. citeturn20search2turn12search4

It cannot, however, use UsageStats to stop another application. UsageStats is fundamentally an **observation source**, not an enforcement API. Combining it with another enforcement adapter is therefore the clean design: time-budget policy can consume UsageStats while an enabled Accessibility, Shizuku or Device Owner adapter enforces the resulting decision. citeturn20search2turn17search2

For website-specific time inside a browser, UsageStats alone is insufficient because the foreground package remains Chrome/Firefox/etc.; it does not tell Focus Fence that the browser currently shows `youtube.com/shorts/...`. That is exactly where the existing scoped address-bar Accessibility strategy has unique value.

### DNS-only VpnService

Android lets a VPN declare specific routes, DNS servers, per-app allow/deny lists, and optional bypass behavior. Android also supports always-on VPN beginning with Android 7; an always-on configuration may be combined with “block connections without VPN” lockdown. citeturn18search0turn20search3

For Focus Fence, **ordinary DNS mode and commitment VPN mode should be conceptually separate**. An ordinary developer-friendly DNS mode should be trivial to pause. Always-on/lockdown is appropriate only when someone deliberately asks the operating system for stronger self-binding, because it directly conflicts with the requirement that PCAPdroid or another VPN be easy to start. Android's one-active-VPN rule makes that conflict unavoidable. citeturn18search0turn20search0

Google Play currently lists firewalls and app-usage tracking among the exceptions that may legitimately use `VpnService`, while requiring the use to be documented in the store listing and restricting sensitive-data collection or traffic manipulation for monetization. Focus Fence's local firewall/use-management purpose therefore falls into categories Play expressly recognizes, although actual approval always depends on the final implementation and disclosures. citeturn13search3turn13search7

### Accessibility

Accessibility is unusually valuable here because it solves problems that DNS fundamentally cannot solve, **without breaking TLS**.

Android allows an Accessibility service with window-content access to inspect the hierarchy of selected application windows. Focus Fence currently uses that to find known browser address-bar view IDs and a bounded heuristic fallback. citeturn13search0

It is nevertheless a brittle integration boundary. Browser developers can rename resource IDs, restructure their UI, stop exposing a particular node, localize descriptions, or otherwise alter the accessible hierarchy. Native applications such as YouTube can change tab structures and labels at any release. That brittleness follows directly from depending on another application's UI hierarchy rather than a stable protocol/API; the current `URL_BAR_IDS` map in the uploaded source makes that dependency explicit.

**Product judgment:** Accessibility rules should therefore be thought of as **compatibility adapters** with tested app/version support, not as a universal engine that magically understands every app.

Google Play policy is another reason to keep it narrow. A wellbeing app that is not fundamentally for users with disabilities cannot mark itself as an accessibility tool merely to avoid disclosure requirements. It must disclose the access and obtain affirmative consent, and Google explicitly asks developers to prefer more narrowly scoped APIs where possible. Play also forbids using Accessibility to stop the user from disabling/uninstalling the app except in parental-control or enterprise contexts. citeturn13search7turn13search3

That aligns almost perfectly with Focus Fence's philosophy: **Accessibility can enforce a rule; it should not become the thing that guards Focus Fence from its owner.**

### Shizuku and shell

Shizuku is a particularly interesting middle ground.

Official Shizuku documentation says its ADB-mode service runs with Android's **shell identity**, not with root. The project explicitly warns that shell/ADB permissions vary between Android releases and that shell cannot, for example, access arbitrary applications' private `/data/user/0/...` files. citeturn15search1

On Android 11 and later, a non-root user can start Shizuku through Wireless Debugging without a PC, but the startup procedure must be repeated after **every reboot**. Android 10 and below need a computer for non-root startup, again with reinitialization after reboot. citeturn15search0

That reboot behavior is a disadvantage for “hard parental-control” software but may be almost ideal for **voluntary power-user self-control**: it provides substantially stronger authority while remaining conspicuously reversible and development-friendly.

AOSP's current package-manager shell implementation includes commands for `suspend`, `unsuspend`, `disable`, `disable-user`, `enable`, hiding and other package state operations. Shizuku's entire model is to let an authorized app perform calls with the ADB/root-side identity supplied by its service. citeturn16search2turn15search1

So the earlier idea was technically sound:

> **A Shizuku-enabled Focus Fence could plausibly suspend or disable selected installed applications on a stock non-root phone.**

That should still be prototype-tested on each supported Android/OEM combination because ADB permissions are version-dependent and OEMs can behave differently; Shizuku itself explicitly warns about this variability. citeturn15search1

It is also important not to think of Shizuku as “granting ADB to the APK.” That is misleading. **Fact:** the Shizuku service holds the privileged identity and brokers approved calls for the client. A normal APK does not permanently acquire every shell permission merely because ADB was once used. citeturn15search1

From Focus Fence's perspective, that is good. The project could keep its normal app unprivileged and make shell enforcement an explicit capability the user chooses.

### Device Owner

Device Owner is vastly stronger—but it is not just “Shizuku that survives reboot.”

Android's official provisioning model treats a Device Owner as the controller of a **fully managed device**. Current AOSP documentation states that a device owner can only be established on an unprovisioned device; once `USER_SETUP_COMPLETE` has ever been set, the device must be factory-reset before normal Device Owner provisioning. Managed provisioning is deliberately designed to disclose the implications to the user. citeturn17search6turn17search5

That fact changes the product conclusion considerably.

A Device Owner can selectively suspend installed packages. Android says suspended apps cannot start activities, their notifications are suppressed, they disappear from overview/recents, and they cannot present dialogs, make sounds, or vibrate. citeturn17search2turn5view4

A Device Owner can additionally block uninstalling a selected package, impose user restrictions, control VPN configuration and control system Private DNS settings. Android exposes `DISALLOW_CONFIG_VPN`, `DISALLOW_CONFIG_PRIVATE_DNS`, `DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES`, `DISALLOW_UNINSTALL_APPS`, `DISALLOW_APPS_CONTROL`, and `DISALLOW_DEBUGGING_FEATURES`, among other restrictions. citeturn17search4turn5view5

That is **extremely strong enforcement with potentially almost zero additional content observation**.

But it has a high ownership/setup cost. Focus Fence is specifically supposed to remain friendly to developers, so options such as `DISALLOW_DEBUGGING_FEATURES`, disabling VPN changes, kiosk-like lock-task behavior, or wholesale app-control restrictions would contradict the normal product unless someone explicitly transformed a spare/dedicated phone into a commitment device. Android's device-management stack exists primarily for managed organizational devices, not as a lightweight consumer permission. citeturn17search5turn17search6

My conclusion is therefore stronger now than earlier:

> **Device Owner should not be a normal “strict mode.”**
>
> It may be an extremely interesting experimental **Commitment Device** mode, but that is almost a separate deployment model.

## The difficult cases: app installation, YouTube Shorts, encrypted DNS, and self-bypass

### Blocking selected app installations

The answer is now fairly clear.

**Verified fact:** Android has an `ACTION_PACKAGE_ADDED` system broadcast indicating that a new package has been installed. Therefore Focus Fence can learn that a prohibited application has appeared **after installation**. citeturn20search5turn20search10

There is no ordinary public Android API giving a normal third-party wellbeing app a universal “before any installer completes, ask Focus Fence whether package X is allowed” veto.

Device Owner offers `DISALLOW_INSTALL_APPS`, but that restriction means the user is disallowed from installing applications generally; it is not a package denylist saying “TikTok no, Signal yes.” citeturn17search4

Similarly, the “unknown sources” restriction controls sideloading from unknown sources rather than acting as a package-name denylist. Android's documentation specifically notes that its unknown-source concept does not include ADB installs. citeturn5view1

So the practical options are:

| Goal | Mechanism | Result |
|---|---|---|
| “No apps may be installed during commitment period” | Device Owner `DISALLOW_INSTALL_APPS` | Strong, but broad |
| “No sideloading” | Device Owner unknown-sources restriction | Stronger sideload control, not package-specific |
| “TikTok may not be usable” | Device Owner package suspension | Strong and selective |
| “TikTok may not be usable” | Shizuku `pm suspend` / `disable-user` | Selective advanced-mode enforcement |
| “Notice if TikTok was installed” | `ACTION_PACKAGE_ADDED` | Detection only |
| “Prevent TikTok APK from ever finishing installation through any installer” | Normal Focus Fence app | **Not a supported general capability** |

AOSP's current package-manager shell has explicit `suspend`, `unsuspend`, `disable-user` and related commands, while Android's DevicePolicyManager exposes package suspension directly to owners. citeturn16search2turn17search2

**Product judgment:** do not frame this feature as “block installation.” The more honest and technically robust abstraction is:

> **prohibited app policy**

The package may physically exist on disk, but a strong backend can make it unavailable while the commitment is active.

That is actually better for personal wellbeing. Uninstall/reinstall cycles are themselves a common form of bypass. A package being installed but suspended gives the policy engine a stable object to control.

A plausible event sequence is conceptually:

```text
Package installed
      ↓
ACTION_PACKAGE_ADDED
      ↓
Policy: package = prohibited
      ↓
available enforcement backend?
      │
      ├── Device Owner → suspend
      ├── Shizuku      → suspend/disable
      ├── Accessibility→ bounce when opened
      └── none         → notify / record only
```

This is precisely why separating policy from enforcement is valuable.

### YouTube Shorts without blocking YouTube

DNS cannot reliably do this.

A DNS resolver gets names such as `youtube.com`; it does not receive the HTTP URL path `/shorts/...`. HTTPS protects HTTP request data, and HTTP/3 carries HTTP semantics over QUIC with confidentiality and integrity supplied by QUIC/TLS. citeturn19search1turn19search2

Even a **full packet VPN without decryption** therefore does not obtain a dependable:

```text
https://youtube.com/shorts/...
                         ^^^^^^^
```

signal.

A packet-level system could try to reverse-engineer which YouTube backend endpoints happen to correlate with Shorts, but that is not equivalent to semantic path blocking. Shared CDN/API infrastructure and future backend changes make such a rule fundamentally brittle. **Engineering inference:** it would likely produce either bypasses or collateral damage to ordinary YouTube.

For browser use, the existing Accessibility approach is significantly cleaner:

```text
Chrome / Firefox
      ↓
address bar exposes current URL
      ↓
Focus Fence sees /shorts/...
      ↓
local policy
      ↓
Back
```

That reads a small piece of information already exposed in the browser UI and never decrypts the network connection. Android Accessibility APIs explicitly permit retrieving accessible window content when the user has enabled such a service. citeturn13search0

For the **native YouTube app**, the problem is different. There may be accessible nodes that identify the Shorts tab/player/interface, and Focus Fence could react to them, but there is no reason to assume YouTube will expose a stable canonical `/shorts/` URL inside its native UI. A native Shorts blocker would therefore be an app-specific Accessibility compatibility adapter rather than a network policy.

I would classify it this way:

**Browser YouTube Shorts blocking:** credible optional feature.

**Native YouTube Shorts blocking:** credible experiment, but version-sensitive.

**DNS-level Shorts blocking:** wrong abstraction.

**Full-TUN Shorts blocking without TLS decryption:** not semantically reliable.

**TLS MITM solely to block Shorts:** wildly disproportionate.

### Private DNS, DoH and DNS bypass

The current source hardcodes several well-known encrypted-DNS resolver names plus `use-application-dns.net`. Those guards can be useful tactical compatibility measures, but they are not a security boundary.

Android has a system-wide Private DNS feature based on encrypted DNS and gives fully managed Device Owners APIs to configure it or prevent users from changing it. citeturn18search1turn17search4

Separately, any ordinary app that implements its own DNS-over-HTTPS logic can send resolver queries as HTTPS traffic. DNS-over-QUIC likewise exists as a standardized encrypted DNS transport. citeturn19search15

A DNS-only TUN that handles conventional DNS traffic cannot generically recognize an arbitrary DoH request hidden inside an HTTPS connection. And “block every known DoH hostname” is inherently incomplete because another server can offer the same service.

Full-TUN processing improves the situation somewhat. It can see destination IPs and ports and therefore can, for example, recognize or refuse known resolver endpoints or traffic on distinctive ports. But DoH deliberately rides ordinary HTTPS, and modern transport encryption increasingly reduces what middleboxes can infer.

ECH is particularly important. The now-standard TLS Encrypted Client Hello encrypts the ClientHello and protects the SNI hostname and other sensitive fields including ALPN. citeturn19search0

QUIC similarly encrypts all payload data and most transport-control information, exposing less network-visible information than conventional TCP. citeturn19search4turn19search5

So there is a hard technical truth here:

> **A local network filter that refuses to decrypt HTTPS cannot simultaneously guarantee that every application's HTTPS-based name resolution is observable.**

That is not a Focus Fence bug. It is an intentional property of modern encrypted protocols.

This suggests a very important product distinction:

**DNS filtering can be excellent practical filtering without pretending to be an unbypassable network security perimeter.**

For normal users, blocking system DNS plus a handful of common bypass cases may provide excellent actual value.

For a voluntarily stronger configuration, Device Owner can lock system Private DNS configuration. citeturn18search1turn17search4

But even Device Owner control over Android's Private DNS setting does not magically stop an application from implementing arbitrary HTTPS requests that happen to contain encrypted DNS.

To reach that level of enforcement, you eventually enter full firewall/proxy/application-control territory—which is exactly where Focus Fence should demand evidence that the additional complexity is worth it.

### Making Focus Fence harder to bypass

This area benefits greatly from distinguishing several adversaries.

The project is **not** trying to withstand a malicious device owner with unlimited time, recovery mode, a computer, factory reset and knowledge of Android internals. On a phone physically owned by that person, absolute tamper resistance is not a reasonable FOSS wellbeing goal.

The more relevant adversary is:

> “Me, at 11:45 PM, impulsively deciding to undo the boundary that I deliberately chose at 9:00 PM.”

The current ten-minute delay is actually targeted directly at that adversary.

A normal strict mode can protect **Focus Fence's own controls**.

Always-on VPN can add system assistance, but the user still owns VPN Settings; lockdown also blocks network when the VPN is unavailable and conflicts directly with PCAPdroid/other VPN use. citeturn18search0

Accessibility can bounce blocked apps and URLs, but using Accessibility to prevent its own disablement or uninstall would run directly against current Google Play policy for a non-parental, non-enterprise application. citeturn13search3

Shizuku raises the cost of impulsive bypass substantially because the app can perform shell package-management actions. But non-root Shizuku disappears across reboot until the user starts it again, creating a natural escape hatch. citeturn15search0turn16search2

Device Owner is the first level where Android itself can make selected apps unavailable, block uninstall, restrict application controls, restrict VPN configuration and restrict Private DNS changes in ways that survive the Focus Fence process. citeturn17search2turn17search4turn5view5

But crossing into Device Owner also crosses a philosophical boundary: Android now regards Focus Fence as the controller of a fully managed device, and official provisioning normally requires wiping/provisioning the phone accordingly. citeturn17search5turn17search6

So I would define the self-binding spectrum in product terms rather than security terms:

| Commitment level | Meaning |
|---|---|
| **Open** | User can change rules immediately |
| **Friction** | Delayed relaxation like current Strict Mode |
| **Enforced** | VPN/Accessibility uphold configured rules, but Android Settings remain authoritative |
| **Power-user commitment** | Shizuku gives selected policies shell-level enforcement |
| **Commitment device** | Device Owner deliberately turns the phone into a locally self-managed device |

That is much more honest than “weak / medium / impossible-to-bypass.”

## Full traffic and HTTPS inspection: where the cost jumps

There is a real use case for full-TUN processing **before** HTTPS interception, but it is narrower than “then Focus Fence understands everything.”

Android's `VpnService` gives the app raw outgoing IP packets through the TUN descriptor when their routes are captured. A full-route VPN could therefore implement connection-level policy, IP blocking, protocol controls, direct-IP filtering, more aggressive encrypted-DNS mitigation, connection statistics, and other firewall behavior. citeturn18search0

PCAPdroid demonstrates that substantial local packet analysis can be built around this model without root; it uses a local VPN to capture and analyze traffic and offers firewall and packet-capture capabilities. citeturn20search0

But full TUN does **not** automatically grant:

```text
page URL
search text
YouTube video identity
Reddit subreddit
HTTP headers
page contents
```

for encrypted traffic.

QUIC integrates TLS 1.3, HTTP/3 relies on that confidentiality, and modern ECH can hide SNI as well. citeturn19search0turn19search1turn19search4

So a full-TUN Focus Fence would be a **better firewall**, not necessarily a better semantic wellbeing filter.

It would also fundamentally change the battery/performance profile. The current implementation's TUN only receives selected DNS traffic; a default route would make userspace participate in general device networking. **Engineering inference:** even an excellent full-TUN implementation should be expected to have greater CPU, wakeup, throughput and compatibility obligations than the existing DNS-only design because it handles vastly more packets. That needs measurement rather than assumption. Android's current performance tooling supports power and system-level tracing specifically for this purpose. citeturn14search2turn14search25

This is where PCAPdroid is more valuable as something Focus Fence should **coexist with** than something Focus Fence must reproduce. When a developer needs deep traffic inspection, they already have a mature FOSS tool expressly designed for that purpose. PCAPdroid processes traffic locally in its no-root VPN mode. citeturn20search0

That argues for:

> Focus Fence owns **policy and personal boundaries**.
>
> PCAPdroid owns **network inspection/debugging**.

The user should be able to hand Android's VPN slot from one to the other.

### Why HTTPS MITM is particularly unattractive

Modern Android makes universal HTTPS interception deliberately difficult.

Android's current Network Security Configuration documentation says all apps trust pre-installed system CAs by default, while only applications targeting Android 6/API 23 and lower also trust the user-added CA store by default. Apps targeting Android 7/API 24 or later must explicitly choose to trust user-added CAs if they want that behavior. citeturn19search3turn19search8

That alone means:

> “Install Focus Fence's certificate and decrypt every Android app”

is not a general solution on modern Android.

Apps can also implement certificate/public-key pinning; Android's own networking stacks expose pinning capabilities. Even when a local CA has been installed, a connection whose application-level trust policy rejects the interception certificate can fail. citeturn19search3

QUIC/HTTP3 adds further proxy complexity because HTTP/3 is built over QUIC rather than ordinary TCP+TLS, and ECH removes additional metadata that passive interception used to rely upon. citeturn19search0turn19search1turn19search2

And there is a much more important product objection: TLS interception destroys the elegant distinction between “the app knows a domain was requested” and “the app can potentially read what I am sending to that domain.”

Even if Focus Fence is perfectly FOSS, perfectly offline, has no telemetry, and never writes that plaintext to disk, a process able to terminate arbitrary TLS has **observation capability** over information the rest of the product does not need.

That is privacy-invasive in the capability sense even when it is not a privacy leak.

The incremental value needs to be compared against what you already get:

```text
DNS:
    whole-domain decisions

UsageStats:
    app time

Accessibility:
    browser URL/path
    app UI state where deliberately supported

Shizuku:
    strong package enforcement

Device Owner:
    extremely strong package/system policy
```

That is already a large capability surface without decrypting anyone's HTTPS.

**Product judgment:** HTTPS MITM should move from “future advanced feature” to **“research-only hypothesis that starts rejected.”**

The burden of proof should be reversed.

Do not ask:

> “Can we make HTTPS filtering work?”

Ask:

> “Which high-value user policy is impossible with every less-observational mechanism, and how much better would HTTPS inspection make that policy?”

Until there is a strong answer, don't build it.

## Battery, scheduling, lifecycle, and proof of worth

There are no credible source-only grounds for assigning Focus Fence a battery percentage today.

The existing architecture contains several technically promising choices: its TUN descriptor is explicitly put into blocking mode rather than busy-polled; only DNS destination routes enter the tunnel; upstream sockets are protected; its Accessibility service dynamically narrows subscribed packages and suppresses high-volume content-change events when section rules do not exist. Those are all sensible designs visible in the uploaded source.

But “sensible architecture” is not the same thing as measured battery performance.

Android's own documentation now recommends system tracing, Macrobenchmark power metrics and the Power Profiler where practical; Batterystats remains useful for per-UID diagnostics, while Google notes that Battery Historian itself is no longer actively maintained and recommends newer tooling where possible. citeturn14search1turn14search2turn14search26

I would turn your phrase **“actual proved worth”** into an engineering requirement.

A feature that requests greater privilege, observes more information, holds more memory or runs more background code should have an experimental comparison against the simpler baseline.

A reasonable Focus Fence measurement matrix would be:

| Configuration | What it isolates |
|---|---|
| Focus Fence installed, everything off | Application baseline |
| Policy engine only | Core state/DB overhead |
| UsageStats enabled | Time-tracking overhead |
| DNS VPN enabled, no lists | VPN lifecycle + DNS forwarding |
| DNS VPN + realistic lists | Matching/update overhead |
| Accessibility app blocking only | Window-state event cost |
| Accessibility browser path rules | Higher-frequency content events |
| Shizuku connected, no actions | Privileged-service idle impact |
| Device Owner, passive policies | DPC baseline |
| Experimental full TUN | Cost of handling all network traffic |
| Experimental content inspection | Incremental deep-inspection cost |

For long idle testing, `adb shell dumpsys batterystats` can report per-UID approximate power use, network behavior and battery events; Perfetto/system tracing can show thread scheduling and wakeups. citeturn14search13turn12search2

For repeatable shorter workloads, Android's Macrobenchmark `PowerMetric` reports energy/power categories including CPU, memory and network when supported by the hardware. It is currently marked experimental, so I would use it as one measurement rather than the sole truth. citeturn14search2

For memory, CPU and GC behavior, Android recommends its Memory Profiler plus system traces/Perfetto. citeturn12search2

The metrics I would eventually insist on are:

| Area | Useful evidence |
|---|---|
| Idle | battery delta, CPU time, wakeups/wakelocks, process residency |
| DNS | p50/p95/p99 added lookup latency |
| Lists | RAM at 100k / 1m / 5m entries, startup/load time, lookup distribution |
| Accessibility | events/hour by mode, CPU time/event, false-block rate |
| UsageStats | query frequency/cost and budget accuracy |
| Full TUN | throughput, latency, CPU/GB transferred, energy/GB |
| Shizuku | idle service cost plus action latency |
| Reliability | OEM/version-specific failures |
| Filtering worth | false positives, false negatives, actual bypass classes fixed |

That last category matters most. A classifier that consumes 300 MB and 5% extra battery but only catches a handful of cases a good list misses is a failed feature even if it is technically clever.

The same idea should govern timers.

Android warns that exact `AlarmManager` alarms wake a device from Doze and are not efficient for recurring background work; it recommends WorkManager for persistent deferrable tasks. citeturn14search0turn14search22

Many Focus Fence schedules do not need an alarm at all.

Suppose Reddit becomes blocked at 8:00 AM while the phone is asleep.

There is usually no value in waking the phone at precisely 8:00 just to mutate an in-memory boolean from:

```text
reddit = allowed
```

to:

```text
reddit = blocked
```

The policy engine can instead evaluate:

```text
rule + current time → blocked?
```

when a relevant request/app event actually occurs.

**Product judgment:** this is a useful general principle for Focus Fence:

> **Prefer evaluating current policy on demand to waking the device merely to update policy state.**

WorkManager makes sense for things such as deferred list downloads; exact alarms should be reserved for cases where something truly user-visible has to happen at an exact instant. Android specifically cautions against recurrent background exact alarms because they wake devices from low-power states. citeturn14search0turn14search22

This also makes the engine concept better. A policy should be a function of current context, not a pile of timers that have to keep running:

```text
decision =
    policy.evaluate(
        target,
        currentTime,
        usage,
        activeCommitment,
        availableCapabilities
    )
```

That doesn't prescribe a future API. It establishes the correct conceptual direction.

## Recommended Focus Fence capability model

After researching the platform, I would **reject a single privilege ladder** and preserve the two-axis model.

### Observation should escalate only when necessary

The observation progression should roughly be:

```text
Local state
    ↓
package/time information
    ↓
DNS names
    ↓
specific supported UI / browser URL
    ↓
network metadata / full TUN
    ↓
decrypted content
```

UsageStats exposes app activity rather than content; DNS exposes queried names; Accessibility with window-content access can inspect selected UI trees; full TUN gives network packets but modern TLS/QUIC hides application content; TLS interception would cross the final plaintext boundary. citeturn20search2turn13search0turn18search0turn19search0turn19search4

The product rule should be:

> **A policy may request the least observational capability that can correctly classify the target.**

Thus:

```text
block pornhub.com
→ DNS is enough

TikTok limited to 30 min/day
→ UsageStats is enough to measure
→ package enforcement handles the result

block youtube.com/shorts in Chrome
→ scoped browser URL observation

block native YouTube Shorts
→ app-specific Accessibility experiment

block TikTok package during class
→ package identity only
→ Shizuku or Device Owner

block a known malicious IP
→ full-TUN might eventually be justified

classify arbitrary page text
→ only then ask whether deeper content observation earns its cost
```

### Enforcement should be independently selectable

A parallel enforcement progression looks more like:

```text
inform
  ↓
friction / delayed relaxation
  ↓
DNS refuse
  ↓
Accessibility Back/Home
  ↓
shell package suspension
  ↓
Device Owner OS policy
```

Android provides real package suspension to Device Owners, while AOSP exposes suspend/disable operations to shell. These mechanisms do not require the application to decrypt or inspect the package's network traffic. citeturn17search2turn16search2

This gives Focus Fence something genuinely distinctive.

A user could choose:

> “I want extremely strong app enforcement, but almost zero content observation.”

That would mean something like UsageStats + Shizuku/package policy, while leaving the VPN entirely off.

Another user could choose:

> “I want strong porn/ad/malware domain filtering but don't care about app blocking.”

That could be DNS VPN + lists, no Accessibility, no shell.

Another could choose:

> “I'm an Android developer. I normally want all of it, but right now PCAPdroid owns my VPN.”

They keep policy, budgets, Accessibility and optionally package enforcement while stopping only the DNS network adapter. Android's one-VPN design and PCAPdroid's local-VPN architecture make this a genuinely valuable configuration rather than an edge case. citeturn18search0turn20search0

### The resulting capability tiers

I would presently classify the mechanisms this way:

| Capability | Research verdict |
|---|---|
| Local policy/rules/schedules | **Foundation** |
| Existing strict-delay commitment | **Keep; good philosophy** |
| UsageStats app-time measurement | **Strongly worth researching/prototyping** |
| DNS-only VPN | **Core optional network backend** |
| Ad/tracker/domain lists | **Natural DNS backend capability** |
| Adult/malware/category DNS rules | **Natural DNS backend capability** |
| Scoped browser URL Accessibility | **High-value optional capability** |
| Whole-app Accessibility bounce | **Useful normal fallback** |
| Native-app UI rules such as Shorts | **Experimental compatibility adapters** |
| Shizuku package suspend/disable | **Very promising advanced capability** |
| Detect prohibited packages after install | **Useful supporting mechanism** |
| Selective pre-install veto | **Not available as the simple public API we hoped for** |
| Device Owner package suspension | **Technically excellent, deployment-heavy** |
| Device Owner uninstall/VPN/DNS hardening | **Commitment-device experiment only** |
| Always-on VPN | **Optional commitment setting, not default** |
| VPN lockdown | **Very strong but bad developer default** |
| Full TUN | **Research experiment requiring measurable justification** |
| Known encrypted-DNS mitigation | **Useful best-effort feature** |
| “Unbypassable DoH blocking without deeper control” | **Do not promise** |
| Network-level YouTube Shorts blocking | **Wrong approach** |
| HTTPS MITM | **Reject for normal product; research only** |
| Root requirement | **Reject as core requirement** |

And this answers one of the biggest open questions from the conversation:

**No, Focus Fence does not need “the engine” in the CloudVeil sense.**

It may eventually need **an engine**, but that engine should primarily answer:

> Given an observation, local classifications, user policy, current context and available enforcement mechanisms, what should happen—and why?

It should not primarily answer:

> How do I intercept and classify as much of this person's internet traffic as possible?

Those are very different engines.

The first fits FOSS personal wellbeing.

The second gravitates toward enterprise filtering.

### Google Play and FOSS distribution reinforce this architecture

Current Google Play policy recognizes firewall and app-usage-tracking use cases for `VpnService`, subject to disclosure and other requirements. Accessibility use by a non-disability tool receives substantially greater scrutiny and must be disclosed with explicit consent, while Play specifically prohibits using Accessibility to prevent user disablement/uninstallation outside parental-control or enterprise cases. citeturn13search3turn13search7

That means modular capabilities are not merely cleaner engineering; they make the trust relationship legible:

```text
[ ] App usage access
    Needed for accurate app-time budgets.
    Observes package/time, not page content.

[ ] DNS filtering
    Uses Android VPN slot.
    Sees DNS names handled by Focus Fence.
    Pause when using another VPN or PCAPdroid.

[ ] Browser/app rules
    Uses Accessibility.
    Can inspect UI of selected supported apps.

[ ] Shell enforcement
    Uses Shizuku.
    Can suspend/disable selected packages.
    Non-root Shizuku must be restarted after reboot.

[ ] Commitment Device
    Device Owner.
    Major setup implications.
```

Each capability has a concrete reason for being there.

That is much stronger privacy communication than simply saying “everything stays local.” Locality is important, but **capability minimization** is more meaningful.

A local application that never receives plaintext cannot accidentally leak plaintext it never had.

### The most important conclusions to carry into later research

The Android research changes or strengthens several working hypotheses from our earlier discussion.

**Verified:** the current DNS-only VPN architecture is legitimately narrow. It still monopolizes Android's VPN slot, so easy pause/disable is a hard product requirement rather than merely a nice feature. citeturn18search0turn20search0

**Verified:** UsageStats offers a VPN-independent, content-light signal for app timing and is better suited to true app-time budgets than inferring foreground use from DNS activity. citeturn20search2turn12search4

**Verified:** Accessibility can support Focus Fence's browser/UI strategy, but it has real privacy potential and Play-policy constraints. The current source's dynamic package/event scoping is therefore an important design strength. citeturn13search0turn13search3

**Verified:** non-root Shizuku is not permanent ADB privilege attached to Focus Fence. It brokers shell-identity capability and must be started again after every reboot in non-root mode. This makes it powerful but naturally reversible. citeturn15search0turn15search1

**Verified:** AOSP shell currently exposes package suspend and disable operations. This makes the shell-level “strong app blocker” idea technically credible enough to prototype later. citeturn16search2

**Verified:** Device Owner can provide dramatically stronger selective app enforcement, uninstall protection, VPN/private-DNS policy and other restrictions. But official provisioning is a fully managed-device operation and generally requires an unprovisioned/factory-reset device. citeturn17search2turn17search4turn17search6

**Verified:** Android does not expose the simple selective pre-install denylist we were imagining. “Prevent every installation of TikTok while allowing arbitrary other installs” is better modeled as detecting installation and then selectively suspending/disable the package using a privileged enforcement backend. citeturn20search10turn17search2turn16search2

**Verified:** DNS and passive packet inspection cannot reliably distinguish `/shorts/` from ordinary YouTube HTTPS traffic. HTTP/3 is encrypted over QUIC, and ECH increasingly hides even TLS hostname metadata. citeturn19search0turn19search1turn19search4

**Product judgment:** browser-path Accessibility is therefore a much better Shorts solution than deeper networking, while native-app Shorts rules should remain explicitly experimental.

**Verified:** user-added CAs are not trusted by default by modern-target Android apps; therefore universal local HTTPS MITM is not a realistic “just install a certificate” architecture. citeturn19search3turn19search8

**Product judgment:** TLS interception presently has poor fit for Focus Fence because its incremental wellbeing value is uncertain while its privacy capability, compatibility burden and implementation complexity are enormous.

**Engineering inference requiring measurement:** the existing DNS-only VPN should be substantially cheaper than a full-TUN architecture, but the repository should not claim negligible/zero battery impact until controlled tests establish it. Android's current measurement tooling is sufficiently good to make battery a measured project invariant rather than marketing language. citeturn14search2turn14search13turn14search26

The strongest architecture-level conclusion is therefore not “add Shizuku” or “don't add HTTPS.”

It is:

> **Focus Fence should make Observation, Classification, Policy, and Enforcement separable concepts.**
>
> A capability should be activated because a user's policy demonstrably needs it, not because another filtering application happens to have it.

That model survives every important constraint you've raised: FOSS, developer use, PCAPdroid coexistence, battery, local processing, adult filtering, ad blocking, custom lists, app budgets, Shorts blocking, stronger voluntary commitment, and future experimentation.

And it gives the project a rigorous answer when a contributor someday proposes something technically impressive:

> **What user boundary becomes meaningfully better because of this feature; what new information must Focus Fence observe; what privilege does it require; what does it cost in battery and compatibility; and can we prove that the simpler mechanism is inadequate?**

For this particular project, that may be the most important “engine” of all.