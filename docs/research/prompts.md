# Ready-to-paste research prompts

> **Tier:** Meta · **Audience:** whoever is about to run a research question — in a browser, or an
> agent session with live web access · **Use when:** you're picking a row from
> [`../research-handoff.md`](../research-handoff.md)'s queue and want the full prompt already
> written, not just the one-line question from the table.

Each block below is self-contained: paste it whole into Fable, Claude, or ChatGPT deep research —
no extra context needed. They're heavier than `research-handoff.md`'s generic template on purpose:
each one states *what decision the answer feeds* and *what would change as a result*, so the
research doesn't come back as a pile of facts nobody can act on.

**After running one:** save the raw output to `docs/research/YYYY-MM-DD-<topic>-<engine>.md` before
touching anything else, then follow the merge-back steps in `research-handoff.md` §"Handoff
procedure". Item 1 (license choice) is closed — no prompt needed.

---

## Q2 — Filter-list licensing (blocks W11, real legal question)

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

## Q3 — Shizuku on non-root (blocks W12)

```
I'm evaluating Shizuku (the Android userspace-privilege-elevation tool,
github.com/RikkaApps/Shizuku) as an enforcement mechanism for an app-blocking feature in a
non-root Android app. Specifically I want to know, as Shizuku exists today (2026):

1. On a genuinely non-rooted device (ADB-pairing/wireless-debugging activation, not root),
   can a Shizuku-privileged app suspend or disable another installed app's package
   (PackageManager.setApplicationEnabledSetting or setPackagesSuspended equivalent)?
2. What Android permissions/API level does that actually require through Shizuku's
   shell-privilege model?
3. What is the real setup friction for an end user — is it a one-time ADB pairing that
   persists, or does something need re-doing regularly beyond reboots?
4. Does the privilege genuinely require the user to restart the Shizuku service after every
   device reboot on non-root, or has that changed? If it's now possible to run persistently
   without root (e.g. via a always-on ADB-over-WiFi shizuku_starter or similar), say so.
5. Is there a maintained wrapper library (e.g. rikka.shizuku:api) with a stable-enough API
   to build against, and how actively maintained is the project as of your knowledge cutoff
   — check for recent commits/releases, not just the README.

This feeds a real design decision (docs/work/W12-shizuku-enforcement.md): if suspension
needs root, or the reboot-restart requirement is gone (making it accidentally closer to a
"prevention" mechanism than "friction" — which this project deliberately does not want,
see its own D-012), that changes or kills the feature, not just its implementation detail.
Cite Shizuku's own docs/issue tracker/source where possible — general knowledge of
Shizuku is likely stale.
```

---

## Q4 — `UsageStatsManager` behaviour (blocks W10)

```
I'm building an Android feature that enforces daily foreground-time budgets per app
(e.g. "20 minutes of Instagram") using UsageStatsManager, targeting API 26+ (minSdk),
compiling against API 34.

Please answer, with citations to official Android docs and any well-known gotchas:
1. What exactly does UsageStatsManager.queryUsageStats / queryEvents report as "foreground
   time" for a package — is it genuinely time-in-foreground, or does it include time in a
   picture-in-picture/background-playback state that a user wouldn't consider "using" the
   app?
2. How is the PACKAGE_USAGE_STATS special permission granted — confirm it's a Settings
   deep-link (ACTION_USAGE_ACCESS_SETTINGS) rather than a runtime dialog, and describe the
   actual flow a user goes through.
3. What are the known accuracy limitations — bucket granularity (is "today so far" reliably
   queryable at fine granularity, or does the API bucket by larger intervals for recent
   data), any documented lag between an app being used and the stats reflecting it, and any
   behavior differences across OEMs (Samsung/Xiaomi's aggressive background-management
   layers are known to interfere with some usage-tracking APIs — note if that's real for
   UsageStatsManager specifically or if that's a different API).
4. Does querying it per-event (say, on an accessibility TYPE_WINDOW_STATE_CHANGED event)
   have a meaningful CPU/battery cost, or is it cheap enough to call directly without
   caching?

This feeds a real implementation (docs/work/W10-app-budgets-usagestats.md) that currently
assumes "query today's foreground time per package" is straightforward — if the API's
actual granularity or lag makes that unreliable for a same-day live budget, the design
needs to change before code is written, not after.
```

---

## Q5 — F-Droid inclusion requirements

```
I maintain an open-source Android app that uses both android.net.VpnService (a local,
DNS-only filtering VPN — no traffic leaves the device except relayed DNS queries) and
AccessibilityService (to read browser address bars for URL-path-level blocking, and to
detect app foreground state). No analytics, no network calls except the DNS relay itself
and optional third-party blocklist downloads.

I want to know F-Droid's current (2026) requirements for a first submission:
1. What are the reproducible-build requirements, concretely — does F-Droid build from
   source itself, and if so what constraints does that put on the Gradle build (no
   network access during build, no closed-source deps, specific plugin versions)?
2. What "anti-feature" labels, if any, would likely apply to an app using VpnService and
   AccessibilityService for this stated purpose, and how are anti-features disclosed to
   users in the F-Droid client?
3. What is the actual review process and realistic timeline for a first-time submission —
   is there a merge-request-based metadata submission flow, and what commonly causes
   rejection or long delays?

Cite F-Droid's own documentation (f-droid.org/docs, the fdroiddata contribution guide)
rather than general impressions — this is a real submission a maintainer is planning, not
a hypothetical.
```

---

## Q6 — Google Play policy on VpnService + AccessibilityService

```
Does Google Play's current developer policy (as of 2026) permit an app that combines
android.net.VpnService (used only for local DNS filtering — no user traffic is proxied or
inspected beyond the DNS query itself) with AccessibilityService (used to read browser
address-bar URLs for path-level website blocking, and to detect foreground app package
for app-blocking)?

Specifically:
1. Is this combination publishable at all, or does Play's Accessibility API policy
   effectively exclude apps whose core function isn't accessibility for users with
   disabilities?
2. What declarations/disclosures does the Play Console require for AccessibilityService
   usage (the permissions declaration form, the "restricted permissions" justification
   flow)?
3. Has Play's policy on this changed recently, or is there a well-known precedent of a
   similar app (a website/app blocker using this combination) being approved or rejected —
   name one if you can find it, with a source.

This is currently moot for the immediate release plan (GitHub Releases, not Play), but
feeds a real future decision, so don't soften an accurate "this would likely be rejected"
answer just because it's not urgent. Cite Play's actual policy pages, not general
developer-forum sentiment.
```

---

## Q8 — Android VPN coexistence (low priority, confirms a permanent constraint)

```
As of 2026, does Android offer any mechanism that would let two VPN-like services run
concurrently, or let one app route only specific traffic (e.g. DNS on port 53) through its
own VpnService.Builder tunnel while a separate, user-installed VPN app (WireGuard,
PCAPdroid, etc.) continues to hold the system's single active-VPN slot for everything
else?

I'm aware of the standard answer that Android allows only one active VpnService (or one
platform VPN + one always-on VPN in specific configurations) at a time. I want to know if
anything has changed in recent Android releases (14/15/16) — a new multi-VPN API, a
per-app-routing capability exposed to third-party apps (not just Settings > Network > Per-
app VPN a device owner/enterprise profile could set), or a documented exception.

Expected answer is "no, still one slot, full stop" — if that's correct, say so plainly
with a citation to the current VpnService documentation; this confirms a permanent design
constraint the whole project already assumes. If something has actually changed, that's a
real finding worth flagging clearly since it would open new design options.
```

---

## Q9 — Signing-key custody prior art (helps unblock the release)

```
I'm about to make a one-way-door decision for an open-source Android app distributed via
GitHub Releases (not yet on any app store): who generates and holds the release-signing
keystore, and whether GitHub Actions CI should sign releases automatically from a repo
secret, or releases should be signed by hand by a maintainer.

Context: it's a two-person project (one repo owner with admin access, one contributor).
Signing key identity is permanent — rotating it later forces every existing user to
uninstall and reinstall, losing local app data (there's no account/cloud sync).

How do comparable FOSS Android privacy/filtering tools handle this, specifically:
1. RethinkDNS
2. NetGuard
3. personalDNSfilter
4. AdAway

For each, if you can determine it from their public repos/docs/release process: is CI
(GitHub Actions or similar) trusted to hold the signing key as a secret and sign releases
automatically, or is signing done by hand by a maintainer on their own machine? Is there
any documented practice for backup/escrow of the keystore (e.g. splitting custody, keeping
an offline backup, using a hardware key)? Has any of these projects had to explain what
happens if the key-holder becomes unavailable?

This doesn't need to produce a single "right answer" — it needs to give two people
something concrete to react to instead of deciding from a blank page. Cite what you find
(repo files, CI workflow YAML, docs, issue discussions) rather than general best-practice
advice about signing keys in the abstract.
```

---

## Q10 — SafeSearch hostnames + YouTube restricted-mode header (needed for W14)

```
I'm implementing DNS-level SafeSearch enforcement for a filtering app: rewriting DNS
queries for major search engines to their "safe" hostname variants, purely at the DNS
layer (no HTTP header injection, no traffic inspection).

Part 1: What are the current (2026) SafeSearch-enforcing hostnames for Google, Bing,
DuckDuckGo, and Yandex — the DNS-level rewrite targets (e.g. Google's
forcesafesearch.google.com pattern) — and do they still function as documented? Note any
that have changed or been deprecated.

Part 2: Separately — does YouTube's "Restricted Mode" still require the client to send a
`YouTube-Restrict` HTTP header in addition to (or instead of) resolving a specific
hostname like restrict.youtube.com / restrictmoderate.youtube.com? A DNS-layer-only filter
cannot set HTTP headers, so I need to know definitively whether the hostname alone is
sufficient in 2026, or whether restricted mode silently does nothing without the header.

Part 3: How do AdGuard Home and Pi-hole — two mature, widely-used open-source DNS
filtering tools — actually implement the SafeSearch rewrite in their source? Specifically:
do they rewrite the DNS answer's A/AAAA record only, or do they also rewrite the CNAME/
answer-name in a way that matters for how browsers validate the response? Cite their
source (github.com/AdguardTeam/AdGuardHome, github.com/pi-hole/pi-hole) if possible.

This feeds a real feature (docs/work/W14) — if YouTube restricted mode genuinely can't
work at DNS level alone, it stays permanently out of scope (already provisionally parked
as P-002) rather than shipping a control that silently does nothing, which is explicitly
the worst failure mode this project has decided to avoid.
```

---

## Q11 — CloudVeil / on-device NSFW classification as prior art (investigative only)

```
I'm evaluating whether on-device NSFW image/content classification is now practical enough
that a privacy-focused, battery-conscious Android app should reconsider a prior decision
to reject any form of content inspection (no TLS/SNI inspection, no traffic routing, no
image/text classification — DNS-hostname-level blocking only).

I want real technical detail, not marketing claims, about CloudVeil
(github.com/CloudVeil or the relevant org — verify the actual project) and any comparable
open-source on-device NSFW/content classification project:

1. Model size and what runs on-device vs. requires a server call — is inference genuinely
   local with nothing uploaded, or does "on-device" marketing hide a network round-trip for
   part of the pipeline?
2. Measured battery/CPU cost of running the classifier — real numbers if published
   (inference time per image, background service battery drain), not vendor claims without
   data.
3. Reported false-positive/false-negative rates, and whether independent testing
   corroborates the project's own numbers.
4. What Android capability the classifier actually needs to see content in the first place
   — does it require an accessibility service reading screen content, a VPN routing all
   traffic (not just DNS), or something else? This determines whether it's even compatible
   with a DNS-only architecture at all, independent of whether it's a good idea.

This is explicitly investigative — finding a good implementation does not mean build one
here. It exists to check whether a previously-parked rejection (P-003 in this project's
own docs, reasoned from privacy/battery/technical-feasibility principles) still holds now
that real prior art can be examined instead of reasoned about abstractly. If the prior art
turns out to require server-side inference, cloud upload, or a fundamentally different
traffic-observation model, say so plainly — that's a meaningful finding on its own, not a
non-answer.
```
