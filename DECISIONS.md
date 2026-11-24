# DECISIONS

Numbered log of *why*. [`FRAMEWORK.md`](FRAMEWORK.md) carries the status; this carries the reasoning, so it doesn't
have to be re-derived or re-argued.

Format: decision, date, reasoning, and what would reopen it.

---

## D-001 — The VPN routes DNS only, and never widens

*Decided: 2026-07-23 (in code, by @sidewinderzz). Recorded: 2026-08-14.*

The tunnel adds `/32` and `/128` routes for its own two synthetic DNS servers. All other traffic
takes the ordinary network path and never enters the process.

**Why:** it is the entire reason battery and throughput cost are negligible, and it means the app
never has the *ability* to see user traffic — a privacy property enforced by architecture rather
than by promise.

**Reopens if:** a validated requirement is impossible without it. Not before. "It would let us
block more" is not sufficient — all four audits independently reject full-TUN filtering.

## D-002 — `dns/` stays free of Android types

*Decided: 2026-07-23 (in code). Recorded: 2026-08-14.*

`Blocklist`, `BlockRule`, `Dns`, `Packets`, `UrlFilter`, `UsageTracker`, `StrictLock`,
`StatsCodec`, `HostsParser` import nothing from Android; callers inject clocks and timestamps.

**Why:** the whole decision surface is testable as plain JUnit with no emulator. This is the only
reason there is any automated coverage at all.

**Reopens if:** never, as stated. New decision logic goes in `dns/`.

## D-003 — Blocked queries are answered `0.0.0.0`/`::`, not NXDOMAIN

*Decided: 2026-08-13, commit `0552f11`. Recorded: 2026-08-14.*

**Why:** a bare NXDOMAIN carries no SOA record, so clients treat it as uncacheable and re-ask
continuously — measured waking the service thousands of times a day. Block answers now carry an
explicit TTL: 1 hour for ads and DoH guards, 60 seconds for user rules (which change with
schedules and budgets).

**Note:** the README and `DnsVpnService.kt:47-48` still say NXDOMAIN. That stale text is what led
all five external audit documents to describe the block path wrongly. **Any proposal to "restore
correct NXDOMAIN behaviour" is re-introducing a fixed battery regression.**

## D-004 — Research and opinion live in `docs/`, never in auto-loaded files

*Decided: 2026-08-13, PR #9.*

`CLAUDE.md` is descriptive only — architecture, commands, invariants, no proposals. All findings,
audits and plans live under `docs/`, which no tool loads automatically.

**Why:** `CLAUDE.md` loads into every Claude session in this repo, including @sidewinderzz's. A
contributor's opinions about direction shouldn't silently steer the maintainer's sessions.

## ~~D-005 — Personal branches integrate on `shareddev`; `main` is releases only~~ — superseded by D-022 (2026-11-23)

*Decided: 2026-08-14, by @flyboy-byte. Revised twice on 2026-08-15.*

Went through three shapes in one day, kept here because the reasoning at each step is worth
having:

1. **A single shared `dev`, feature branches off it, PR to `main`.** The original plan. Never
   really exercised as designed.
2. **Personal branches (`logandev`, `shelbydev`) straight to `main`, no shared branch.** Tried
   after the first real two-person round — `shelbydev` fast-forwarded into `logandev`, which
   merged into `main` with zero conflicts, so the shared branch looked unnecessary.
3. **`main` → `shareddev` → `{logandev, shelbydev}` — the current shape.** @flyboy-byte's call:
   put a shared integration branch back between the personal branches and `main`, so `main` stays
   releases-only and integration testing has a home that isn't the release branch itself. All
   three branches sat at the same commit (`60724f4`) when `shareddev` was created, so nothing
   moved — this only changes where the *next* round of work lands.

**Consequence:** personal branches fork from and merge back into `shareddev`, not `main`.
`shareddev` merges to `main` deliberately, at release points — not on every sync. `main` is never
committed to directly (restoring the original D-005 intent, just with two personal branches
feeding it through one gate instead of many).

## D-006 — "Finished" means a public GitHub Release

*Decided: 2026-08-14, by @flyboy-byte.*

A signed, installable APK attached to a GitHub Release that a stranger can download and use —
not a CI artifact for the two of us.

**Why it matters beyond packaging:** it changes the standard of proof for the docs. Inaccurate
README claims stop being stale comments and become claims made to strangers about an app that
asks for VPN and Accessibility permissions. See `docs/infrastructure.md` R6 and `docs/risks.md`.

## D-007 — @flyboy-byte decides scope; the original idea is preserved

*Decided: 2026-08-14.*

Focus Fence is @sidewinderzz's project and idea. @flyboy-byte decides direction and scope going
forward, and the original premise — a self-control tool for user-chosen sites, fully on-device,
cheap to run — is kept rather than replaced.

**Consequence:** proposals that redefine what the app *is* need an explicit decision entry here,
not just a roadmap line. `docs/audits/01-product-definition.md` proposes exactly such a
redefinition ("the broad thesis is falsified, a narrower one survives"); that is an outside
reviewer's preference, and it has **not** been adopted.

## D-008 — General FOSS tool, opinionated by one use case

*Decided: 2026-08-14, by @flyboy-byte. Full text in `docs/overview.md` § Product intent.*

Focus Fence is a general-purpose FOSS self-control tool whose philosophy and constraints come
from a specific power-user/developer use case — not a tool for one person, and not a general
digital wellbeing app chasing competitor parity.

**Why the middle position is stated explicitly:** each collapse has a distinct failure mode.
"It's for me" overfits every abstraction to one workflow. "General wellbeing app" imports every
feature a competitor has. The use case sets the constraints; the implementation generalises them.

**Consequence:** feature proposals are judged against the boundary examples in `docs/overview.md`, not
against a competitor matrix.

## D-009 — Boundaries are the product; mechanisms are implementation

*Decided: 2026-08-14.*

The app is not fundamentally an ad blocker, porn blocker, app timer or VPN. The user expresses a
boundary; the app chooses the mechanism.

**Consequence, and it's a real one:** the code has no boundary object. Domains carry four rule
modes, apps carry none, and the two live in unrelated stores. `docs/approach.md` B2 exists because of this
decision, and two of the six boundaries in `docs/overview.md` are currently inexpressible.

## D-010 — Least invasive mechanism wins; privacy includes not observing

*Decided: 2026-08-14.*

Privacy is not only "we don't upload data" — the app should avoid observing information it does
not need. Where two mechanisms enforce the same boundary, the one that observes less wins, even
if the other is more powerful. More privilege is not automatically better.

**Consequence:** this is a *test* proposals must pass, and the current code fails it in one place.
Whole-app blocking is enforced by the accessibility service, which reads browser address bars;
Shizuku package suspension would enforce harder while observing almost nothing (`docs/approach.md` B7).
Reading address bars remains justified for section rules, where nothing cheaper can see a path.

It also settles HTTPS interception permanently: it is rejected on principle, not on feasibility.

## D-011 — Separate observation, classification, policy and enforcement

*Decided: 2026-08-14.*

Four distinct concerns, four distinct layers.

**Consequence:** `DnsVpnService.ruleBlocks()` currently mixes all four — it observes DNS packets,
evaluates schedules, accrues budgets and synthesises the block answer. `docs/approach.md` B1 splits it.
This decision also means enforcement mechanisms are pluggable by design, which is what makes B7
(Shizuku) a swap rather than a rewrite.

## D-012 — Threat model is impulsive circumvention, not a determined owner

*Decided: 2026-08-14.*

Target: "I decided at 9 PM I don't want this available, and at 11:30 PM I impulsively try to undo
it." Explicitly **not**: an owner with ADB, recovery, root, factory reset and unlimited effort.

**Why it matters:** it gives a stopping rule. Friction is calibrated to the impulse, so
"make bypass harder" is not self-justifying — it has to beat the impulse threshold, and past that
it costs device ownership for nothing. Strict mode's existing 10-minute delay and 5-minute window
are a reasonable expression of this (still an unvalidated default). Device Owner is rejected under
it: it fights an adversary this project doesn't have.

## D-013 — The VPN is optional; the rest of the app survives without it

*Decided: 2026-08-14.*

Focus Fence must keep working when PCAPdroid, WireGuard, another VPN or a development experiment
holds Android's single VPN slot. The VPN must stay easy to stop.

**Consequence:** currently only half true, and this decision is what makes that a bug rather than
a limitation. Section rules and app blocking run in the accessibility service and survive; domain
schedules and budgets are evaluated inside `DnsVpnService` and stop dead. `docs/approach.md` B1 and B4.

**Interaction with strict mode:** strict lock refusing `ACTION_STOP` is *not* a violation — it's
the impulse filter from D-012, and the 10-minute unlock path keeps the owner in control. The line
is friction, not prevention.

## D-014 — No premature SDK or general policy engine

*Decided: 2026-08-14.*

Longer term the policy/filtering logic should be clean enough for another developer to use or
extend. But abstractions have to prove their value in the app first.

**Consequence:** B2 designs a boundary model around the six concrete examples in `docs/overview.md`, not
around a general policy language. Guards the source-model work (B6) from becoming a matcher
rewrite — audit 03 found the existing `HashSet` adequate at ~100k entries.

## D-015 — MIT license

*Decided: 2026-08-15 by @flyboy-byte.*

MIT, over the MPL-2.0 / Apache-2.0 / GPLv3 candidates from audit 03. Rationale: it costs the
least to everyone, imposes no obligations that would complicate a future library extraction
(D-014), and matches StevenBlack/hosts — the project's main upstream data source — so there is no
mismatch between what we consume and what we publish. The reciprocity MPL or GPL would buy is
worth little here: the value of this project is the design and the maintenance, not code a
closed fork could meaningfully strip.

Apache-2.0's explicit patent grant is the one real thing MIT gives up. Accepted — there is no
patentable subject matter here and no corporate contributor to worry about.

**Consequence:** `LICENSE` at the repo root, `SPDX-License-Identifier: MIT` in the README, no
per-file headers. Unblocks W3 and therefore the first release. Needed @sidewinderzz's
acknowledgement — copyright is shared and one maintainer cannot license the other's work.

**Acknowledged 2026-08-15 by @sidewinderzz**, stated directly when asked during the session that
produced this commit. Recorded here by the agent that asked, so if a stronger artefact is wanted
before a binary ships, a signed commit or an issue comment from @sidewinderzz supersedes this
line.

## D-016 — Adult-content blocking is a category list plus SafeSearch, not a content engine

*Decided: 2026-08-15.*

Adult-content filtering is in scope as a **first-class category**, implemented with two
mechanisms and no third:

1. **Curated category lists** (HaGeZi NSFW, StevenBlack's porn extension) loaded as ordinary
   sources through the multi-source work (B6/W11). These are hosts-format files that drop
   straight into the existing matcher — architecturally this is *free*.
2. **DNS-level SafeSearch enforcement** — rewriting queries for the major search engines and
   YouTube to their safe-search hostnames, so image and video search stops surfacing the
   content that a domain list can never catch.

Explicitly **rejected**: any "deeper layer" engine — TLS/SNI inspection, full traffic routing,
on-device image or text classification, or a content-scanning proxy.

**Why the deep engine is the wrong answer here, specifically:**

- It requires seeing content. This app's privacy claim (D-010) is not "we don't upload your
  data", it is "we don't look at it in the first place". A classifier that judges whether a page
  is pornographic must first read the page. That is the single largest thing this project has
  said it will not do, and adult content is precisely the category where being watched is most
  invasive.
- It requires routing everything. The `/32`+`/128` route trick is the whole reason battery cost
  is negligible. Inspecting content means carrying real traffic, which breaks the premise the
  README sells.
- It mostly cannot work anyway. TLS hides paths and payloads; ECH is eating SNI. The realistic
  ceiling for on-device inspection in 2026 is hostname-level — which is exactly what DNS already
  gives us, for free.
- The threat model doesn't need it (D-012). The adversary is an impulse, not an adversary. A
  category list plus SafeSearch defeats the impulse; nothing defeats a determined owner, and the
  attempt costs privacy, battery and complexity.

**Where the honest gap is:** mixed-content platforms (Reddit, X, TikTok, Telegram, Discord) serve
both adult and non-adult content from the same hostnames. DNS cannot separate them, and neither
can anything else short of reading content. This is **parked, not solved** — see
[`docs/parked.md`](docs/parked.md) P-001. The app says so plainly in the UI and offers the whole
domain as an ordinary target; it does not imply a precision it doesn't have. YouTube restricted
mode is parked separately (P-002) because it may need an HTTP header a DNS filter cannot set.

**Consequence:** adult filtering ships as W14, sequenced after W11. No new architecture. The
category toggle must be protected by strict mode (D-012's friction is more load-bearing here
than anywhere else in the app).

---

## D-021 — One release signing key for every Focus Fence release

*Decided: 2026-11-23 by @flyboy-byte. Resolves O-002.*

**Numbered D-021, not D-017,** because the unmerged `shareddev` branch already uses D-017 to D-020 (allowlist,
partner-held keys). **It supersedes the 2026-08-18 O-002 resolution on `shareddev` (commit `9214641`)**, which
said @sidewinderzz would generate and hold an RSA 4096 key and sign by hand. That key was never used: every
release since 2026-11-17 has been CI-signed with the key below, so this records what actually happened and
needs @sidewinderzz's agreement, since it reverses what he was assigned.

Every release APK of this app, from any branch, is signed with the key already held in this repo's
Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, added
2026-11-17). It has signed every `v*-accountable` release since then (up to 2.10.0), and
those builds are on real phones, so it already is the app's identity: changing it now would force
every install to uninstall and lose its rules, exactly the failure O-002 warned about.

**The key, so anyone can check a release is genuine** (public values, not secrets):

| | |
|---|---|
| Certificate SHA-256 | `2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671` |
| Public key SHA-256 | `deb822114c362ad960c2d8c3f42cfce9bcd1b0926625aabbaacc0cb2b60f3bb2` |
| Subject | `CN=Focus Fence Accountable, OU=flyboy-byte, O=Focus Fence, C=CA` |
| Algorithm | RSA 2048, APK Signature Scheme v2 |
| Valid | 2026-11-16 to 2054-04-03 |

Check one with `apksigner verify --print-certs <apk>`: the certificate SHA-256 must match.

**Answers to O-002's questions:**

1. *Who generated and holds it:* @flyboy-byte.
2. *Does the other maintainer hold a copy:* not as a file. @sidewinderzz, as repo admin, controls the
   secrets and the workflows that use them; that is the second point of control.
3. *Backup:* @flyboy-byte holds the original keystore file (located 2026-11-23) and a second copy recovered from
   the secrets the same day (fingerprint checked against the certificate above). Both stay out of the repo, chats and
   synced drives; location deliberately not written here. Keeping one copy offline and encrypted is the holder's job.
4. *CI or by hand:* **CI signs**, from the secrets above. The doc's hand-signing recommendation was
   not followed; the risk is contained because only `release.yml` reads the secrets and it runs on
   pushed `v*` tags only. `android.yml` runs on `pull_request` and never references them, and GitHub
   does not pass secrets to fork PRs.

**Deviations from `docs/signing-key-custody.md`, accepted:** RSA 2048 instead of 4096 (Android and
every store accept it; 2048 is still the common default), and about 27 years of validity instead of
30 or more (it outlives any plausible life of this app; Play's own floor is 2033).

**Rules that follow:**

- Any signing config (including the one proposed in #11, which reads `FOCUSFENCE_*` variables) must
  use **this** key. A second key for the same application id is never acceptable.
- Never add these secrets to a workflow that runs on `pull_request`, and never print them.
- A build without the secrets (forks, local) stays unsigned or debug-signed, never a "release".
- Debug and CI artifacts are signed with debug keys and do not install over a release; that is
  expected, not a bug.

**What would reopen it:** the key leaking (then: new application id, announce, reissue) or being lost
(then: same, since there is no recovery). Moving to hand signing later keeps the same key and does not
reopen this.

---

## D-022 — `main` is the only long-lived branch; short task branches merge by PR

*Decided: 2026-11-23 by @flyboy-byte, with @sidewinderzz. Supersedes D-005. (D-021 is the signing key, PR #15.)*

D-005's `shareddev` gate was never used as designed: work sat on `shareddev`/`logandev` from 2026-08-18
and never reached `main`, while the accountability work went from its own branch straight to `main`
in #12. Both of us now work through Claude sessions that open PRs, so the gate adds a second place for
work to strand without adding review. The shape that matches how we actually work:

- **`main` is the only long-lived branch**, the default branch, and is changed by PR. Nothing about a PR is enforced on
  the other person: PRs carry a little awareness for them instead (AGENTS.md).
- **One short branch per task**, from fresh `main`, opened as a draft PR early so the other person (and
  their Claude) can see the area is claimed. Merge within a day or two; head branches are deleted on merge.
- **Releases are tags on `main`**, and the version is bumped only in the release PR, so two builds never
  share a version (two different builds were both called 2.10.0 on 2026-11-23).
- Details, hot files and the cleanup that went with this: [`docs/collaboration.md`](docs/collaboration.md).

**What would reopen it:** a third regular contributor, or a release cadence that needs a stabilisation
branch.

---

## D-023 — A server operator cannot read a user's activity

*Decided: 2026-11-23 by @flyboy-byte. Replaces the "server-readable records" privacy tier.*

Anyone a server is shared with is an independent user: nothing links two people unless they choose to (an invite,
an app pairing, a group with fingerprint approval). The one place an operator could still read someone's history
was the email/web partner path, where the phone uploaded its raw activity log and the server built the report. So:

- **The phone never uploads activity records.** For an email/web partner it builds the same `Digest` the partner app
  gets, seals it with AES-256-GCM under a per-phone report key (`dns/ReportSeal`), and uploads the ciphertext hourly.
- **The key never reaches the server.** It travels to partners after the `#` of the invite link (or a resent key
  link), which browsers never send. The report page decrypts it in the partner's browser with WebCrypto
  (`server/src/main/resources/static/report.js`) and merges the hourly slices.
- **Alerts are the phone's alert lines only** (`Digest.alertText`: "Filter turned off", never a site). Weekly emails
  are a link, not content. Silence detection still uses check-ins.
- **Older apps** that still upload batches: the server checks them for alerts in memory and never stores them.
  Readable records left by earlier versions are deleted at startup.

**What the operator still sees:** partner emails (and phones, for SMS), alert texts, check-in and upload times and
sizes. **What this does not stop:** an operator who changes the server to serve a key-stealing page. It stops reading
stored history, not an attack on one's own users; running your own server is the answer to that, and the server code
stays in this repo for exactly that reason.

**Costs accepted:** the report page needs a script (only on `/p/`, `/join`, `/key`, only our own file); a partner on a
new browser needs the link again; the server can no longer cross-check record gaps itself (the phone's digest reports
missing records).

**What would reopen it:** a need for server-side analysis of activity, which this rules out by design.

---

## D-024 — Block feedback: triaged notifications, never a browser block page

*Decided: 2026-11-23. Notification as the default is @sidewinderzz's call in #14; the triage is
@flyboy-byte's proposal on #13, adopted as the direction (his agreement to the triage itself: confirm on that thread).*

A blocked site still gets a `0.0.0.0` answer (D-003), and the browser still shows its own error. Telling a block apart
from bad internet is the app's job, not the browser's: **no CA, no interstitial page** (parked as P-006).

**How the app tells you (from #13/#14):** "When a site is blocked" offers **notification** (default), the **full
"Blocked by Focus Fence" screen**, or **off**. Either is shown only after the accessibility service confirms the browser's
address bar is on the blocked site, so prefetch and other apps' background lookups never trigger it. Without that
service: a quiet notification, at most once per site every 5 minutes.

**What qualifies, by triage** (built 2026-11-24, `dns/BlockTriage`; numbers from one phone, 9,369 blocks in 5 days, 90% trackers):

| Tier | What | What happens |
|---|---|---|
| Silent | ads, trackers/telemetry, DoH bypass guards, non-front-door hosts, screen off | logged only; counts in the report |
| Notice | a user rule, or a high-risk category (adult, gambling, scam/phishing), on a front-door host the address bar confirms | the chosen style: notification or full screen |
| Partner | repeated Notice-level hits (e.g. 3 high-risk in 10 minutes) | a partner alert, like the screenshot `FlagPolicy` |

Rules that go with it: notification text for a category block stays generic ("Blocked: adult content"), so a site name
doesn't sit in the notification shade; within the "security" list, scam/phishing ranks above trackers; each tier's
action (notify me / tell my partner / just log) is a setting, with these defaults.

**As built (2026-11-24):** high-risk = adult, gambling, scam (only the lists the owner turned on). A list block's
"front door" is shape-based (bare name or www./m./mobile.), since lists carry no rule domain. The Partner tier counts only
address-bar-confirmed hits, so it needs the accessibility service: without it the owner still gets the notification, but
partners are never told (a wrong partner alert is worse than a missed one). It fires once per category, then stays quiet
for an hour. Settings → Filtering: "Also adult, gambling and scam sites" and "Tell partners if I keep trying" (on by
default; turning the latter off is behind the `QUIET_BLOCK_ALERTS` friction guard).

**What would reopen it:** a way to show a block page in the browser without a certificate authority (see P-006).

---

# Open — decided before the first release

## ~~O-002 — Signing key custody~~ — resolved by D-021 (2026-11-23)

## O-003 — Whether an allowlist overrides user rules

Scoped in `docs/plans/00-roadmap.md` Tier 1. The conservative answer is that an allowlist should
override only list-sourced blocks, never a rule the user deliberately set — so it can't silently
defeat a boundary someone chose. Not yet decided.

## D-025 — Screenshots to partners: owner-sent and blurred now, automatic later

**Date:** 2026-11-23 · **By:** @flyboy-byte · **Status:** accepted

Level B ships: the owner picks a flagged screenshot on Review and sends it. It is always the `ShotBlur` thumbnail
(24 px long edge, blurred; 48 px since the amendment below), never the clear image, even by the owner's choice, because a partner must never hold a
clear explicit image (roadmap Phase 2) and an owner in a bad moment is exactly when that rule matters. It is sealed
with the report key (D-023), so the server stores ciphertext only. Level C (automatic sending of the same thumbnail)
is the eventual step, gated on the false-flag rate and the conditions in `docs/roadmap.md` Phase 2. Level D (a
clear image) stays ruled out.

**Amended 2026-11-24 by @flyboy-byte:** the thumbnail goes to 48 px on the long edge with one blur instead of 24 px
with two. At 24 px a partner saw colours only and couldn't judge whether a flag was real. Compared side by side on a
real photo: 48 px shows the shape of the scene with no detail; 96 px unblurred was rejected as close to a clear image.
The rule itself is unchanged.

## D-026 — A partner holds one link: the invite link is their report link for good

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

Before this, the report key reached a partner's browser only by opening the invite (or a "key link") in that
browser, so opening an alert email in a different browser showed "the key is missing". Logan: "They should be able to
just hold the link." Now each invite has a long secret (`/i/{secret}`, besides the short typed code). Opened before
joining it is the join form; after joining and confirming the address it redirects to the partner's report, and the
`#k=` key rides along, so the one link works in any browser, any time. Removing the partner kills it. Unconfirmed
partners get a "confirm your email first" page and no report token (the double opt-in rule stands).

Alert emails keep a clickable `/p/{token}` link but can't carry the key (D-023), so they say to open the partner's
own link if the key is missing. Option B, a partner password that unlocks the key from any email link without the
server ever seeing it, is scoped in `docs/plans/09-partner-login.md` and not built. Dropping end-to-end encryption to
make email links work was considered and rejected.

## D-027 — The repo moves to @flyboy-byte and goes public as is

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

@sidewinderzz transferred the repo to @flyboy-byte (`flyboy-byte/focusfence`; GitHub redirects the old URL). It goes
public with its history unchanged. The current files were cleaned first (#40–#44); old commits still show the server
details and an early personal author address, rated low risk in `docs/public-release.md`. A rewritten history was built
and verified but not used: Logan wanted "a clean public repo", and what visitors see is clean, while a rewrite would
change every commit hash, break changelog links and clones, and still need GitHub Support to purge PR refs. The
encrypted keystore-recovery artifact was deleted first, so it never became public. Public also means GitHub Actions
runs free.

## D-028 — Device owner (managed mode) removed; uninstall protection stays

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

Managed mode (Private DNS strategy switch, always-on VPN, settings lock, app suspension, network logging) needed adb
and a phone with every account removed. Logan: "No one would ever do device owner." It was never set up on a real
phone. Removed from the app, Settings, Home and the Guide; `docs/specs/device-owner.md` is kept as history.
Uninstall protection (device admin with no policies, Ever Accountable's model) stays, now on its own small screen
with Turn on / Turn off (the latter behind the `REMOVE_PROTECTION` friction guard). A phone provisioned by an older
version gets one button that clears every policy those versions could set and leaves device ownership.

## D-029 — The app updates itself from GitHub: notify, one tap

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

The app isn't in a store, so without this nobody updates. Once a day (WorkManager, `update/Updates`) it asks GitHub for
the latest release; a newer full release shows one notification per version and a banner on Home ("X is available ·
What's new · Update · ✕", after @sidewinderzz's Tokalot). Nothing downloads until the owner taps; the APK must come
from this repo's release downloads, be this app, be newer, and carry the installed copy's signing certificate, and
Android's installer still confirms. On by default with a Settings switch, because it is the one request the app makes
that the owner didn't set up (GitHub sees the phone's IP). Considered: fully automatic where Android allows (rejected:
Logan chose notify-and-tap); off by default (rejected: people would stay on old versions). If the app ever goes to
F-Droid, this must be off in that build.

## D-030 — No downgrades in the app; a bad release is fixed by rolling forward

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

Android refuses to install a lower version over a higher one; the only on-phone route is uninstalling, which wipes
rules, strict mode and partner links and tells partners the app was removed. So there is no "revert to an older
version" in the app. A bad release is undone with a new one (2.x.1) that reverts the change: every changelog line names
the commit to `git revert`, and the update banner delivers it within a day. Settings → About links to the GitHub repo,
where every release stays available. A settings backup/restore was offered and declined for now.

## D-031 — Release notes carry "What's new"; the privacy pages live on GitHub Pages

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

The release workflow writes a `## What's new` section from `tools/changelog.py notes <previous tag>`; the app reads it
for the update banner, the Update screen and Settings → About → What's new (`UpdateCheck.userNotes` drops commit and PR
references, scope prefixes and the Docs/Maintenance/Tests sections). Releases 2.11 to 2.14 were backfilled. The About,
Privacy, Limits and self-hosting pages (`docs/site`) publish to https://flyboy-byte.github.io/focusfence/
(`.github/workflows/pages.yml`); the server no longer serves them at all (Logan: delete, no redirect), so apps up to
2.14 get a not-found page from that button until they update.

## D-032 — UI direction: plain in a good way, colour only for state, ordered by rank

**Date:** 2026-11-24 · **By:** @flyboy-byte · **Status:** accepted

From Logan's reactions to a mockup and to 2.14: "plain in a good way", not generic AI. Rules for every screen:
- Colour carries state only: green = working, amber = needs you, error colours = off or broken. The fixed greens and
  ambers (`values/colors.xml`, `values-night`) always go through `ui/StatusColors` (Material's
  `harmonizeWithPrimary`), so they sit inside the Material You palette instead of fighting it; everything else is the
  dynamic theme.
- One strong element per screen (Home's status card, Screen checks' Review button); the rest is plain lists with
  dividers, sentence-case headings, short copy. No dashboard tiles, icon circles or uppercase micro-labels.
- Each screen is ordered by rank: what matters and gets used most first, rare and technical last or folded under
  Advanced. Settings: Protection, Blocking, Advanced, About. Partners: partners first, the server connection last once
  set up (first until then).
- Never show the AI's label unless something was flagged.

