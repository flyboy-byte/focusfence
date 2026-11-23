# Group accountability, public release, and landing on `main`

_Scoped 2026-11-23. A plan, not state: `STATE.md` wins. Nothing in sections 2 and 3 is built. Section 4's server
packaging is built and was tested locally (not yet run through the release workflow)._

## 0. What the owner asked for (Logan, 2026-11-23)

1. **Group accountability** for up to 3 or 4 phones; each member is filtered, or only a partner, or both. Make this
   the app's target, but **solo use without any partner must keep working**, in case it is ever released.
2. **Move GitHub forward**: a full release for the app and for the server, with a Docker Compose option for the server.
3. Get this branch to where it **could** merge to `main`, with the confirmation and decision of @sidewinderzz.

## 0b. Product shape: layers, all optional (owner, 2026-11-23)

Logan: keep accountability in the app, but leave the **server optional and screenshots optional**, most layers
**recommended** rather than forced, in the FOSS spirit of keeping it light. Nothing below needs to be built to make this
true of the current code; what it needs is to be **stated, guided and tested**.

| Layer | Default today | Recommended? | Needs a server | Measured cost |
|---|---|---|---|---|
| Core filter (DNS-only VPN, lists, per-domain rules) | on once started | yes | no | negligible (the premise of the app) |
| Local activity and diagnostics log (DNS on, URLs off) | DNS logging on, URL logging off | yes: it is the record and never leaves the phone unless exported | no | small |
| Strict mode and per-action friction | user turns on | yes for accountability | no | none |
| Uninstall protection (device admin) | user turns on (one tap) | yes | no | none |
| On-screen text capture | **off** (checked in code) | optional | no | the biggest accessibility cost (about 21% of app CPU in the 2026-11-23 export) |
| Screenshots + on-device AI scoring | **off** (checked in code) | optional, recommended if you want evidence | no (evidence to a partner needs a partner key) | about 19% to 32% of app CPU |
| Owner review screen | on, only does anything when AI is on | with AI | no | small |
| Partners by email/web | none: **needs a server address** (checked in code) | recommended | yes | upload every 15 min, measured negligible |
| App-to-app pairing and groups | none: **needs a server address** | recommended | yes (blind mailbox only) | same |

Rules this gives us, to keep true as the app grows:
1. **With every optional layer off the app is the lightweight blocker.** No capture, no AI, no uploads, no server contact, no
   accessibility events beyond what rules need. A test or checklist should keep proving it (a release check: fresh install,
   core filter only, confirm no sync/capture work starts).
2. **Server and screenshots are separate switches.** A partner can get alerts without any screenshots; screenshots can be
   on with no partner (owner review only).
3. **Recommended means guided, not forced.** The app should offer a single "Set up accountability" path that suggests the
   recommended layers one at a time, each with its measured cost in plain words, and lets the person stop at any step.
   (Not built: today these are separate settings screens.)
4. **Self-hosting is a first-class option, not a requirement**: the server is a published image with Compose (section 4), and
   nothing in the app is tied to one operator.

## 1. Findings that shape everything (read before planning)

| Finding | Evidence | Consequence |
|---|---|---|
| `main` is releases-only and merged by a human; work integrates on `shareddev` via personal branches | `DECISIONS.md` D-005, `docs/work/README.md`, `NEXT.md` ("Never merge or push to `main`") | Our branch should land on `shareddev`, not `main`. `main` gets it at a release point. |
| `shareddev` is 29 commits past `main` and **a full parallel line of work**: UI redesign, multi-source lists, allowlist, release signing (W4/W5), CHANGELOG, benchmark script | `git diff origin/main...origin/shareddev`: 135 files | Our branch forked from the older default branch and has seen none of it. |
| A trial merge of `shareddev` into this branch conflicts in **25 files**: all the layouts, `strings.xml`, `themes.xml`, `SettingsFragment`, `StatsFragment`, `DnsVpnService`, `UiUtil`, `UrlFilterService`, `FocusFenceApp`, both workflows, README, CLAUDE.md | `git merge-tree origin/shareddev HEAD` (read-only) | Landing is a real merge project, not a setup step. Both sides redesigned the UI. |
| **This branch contradicts recorded decisions** | D-010 (privacy includes not observing), D-012 (threat model is an impulse, not a determined owner; Device Owner rejected), D-016 (**rejects on-device image/text classification**) vs our AI classifier, screenshots, device admin/owner, partner reports | Per D-007, redefining the app needs its own decision entry. These were @flyboy-byte's decisions (Logan's), so he can supersede them; @sidewinderzz owns the project and must agree. |
| Signing key custody (O-002) is an open one-way door | `DECISIONS.md` O-002, W4 | We already publish releases signed with Logan's stable key (cert `2ca769a5…8671`). On `main` that has to be decided on purpose, by both. |
| The repo is **private**, has no `LICENSE` on this branch, and the default branch is not `main` | `gh repo view` | `main` already adopted MIT (D-015, acknowledged by @sidewinderzz). Take that. |
| Our token has WRITE, not admin | `viewerPermission: WRITE` | Branch protection, package visibility and secrets are @sidewinderzz's to set. |

## 2. Landing options (decide before any merge work)

| | What | Cost | Risk | Fits the rules |
|---|---|---|---|---|
| **A. Merge `shareddev` into this branch, PR into `shareddev`, later release-merge to `main`** | Resolve the 25 conflicts once, keep our history | Large: UI reconciliation on both sides, phone-only verification | UI regressions that CI can't see | Yes, after a D-017 |
| **B. Separate product** (own repo or release line "Focus Fence AC"), upstream untouched | Already has its own applicationId and installs alongside | Small | Two codebases drift; pure-core improvements need periodic merging | Sidesteps D-010/12/16 entirely |
| **C. Rebuild on top of `shareddev`**: re-apply accountability feature by feature as small PRs, each an opt-in module (`dns/` pure core, `sync/`, `monitor/`, `server/` mostly new files; thin hooks into their UI) | Biggest, cleanest history | Largest | Slowest, but each step is reviewable and revertable | Best match for the work-order style |

**Recommendation:** write the decision first (section 5), then **C if the plan is to land upstream, B if the plan is "ours,
maybe release later."** Do not start A mechanically: a 25-file conflict resolved without a decision on scope produces a
merge nobody can review.

## 3. Group accountability (design proposal; none built)

### Model
- A **group** is 2 to 4 members. Each member has a **role**, chosen per person and changeable with friction:
  **Filtered** (runs the filter, uploads their own log), **Partner** (receives others' reports, can issue unlock codes),
  or **both**. A **partner-only** member installs the app but never starts the VPN or uploads anything.
- **Solo stays the default.** No group, no server, no partner: nothing in the app changes. Groups are opt-in under
  Partners & reports.
- Email/web partners keep working beside group members (a filtered member can have both).

### Visibility and authority (**item 1 decided 2026-11-23**: everyone sees every filtered member by default; the rest are proposed defaults)
1. Each filtered member chooses who sees their report; default **every other group member**. No member sees another's
   unless that member allowed it.
2. **Any single partner's unlock code** unlocks a filtered member's strict-mode wait (as today with one partner).
   Optional later: require 2 of N for the highest-friction actions.
3. **Leaving or removing a member always tells the group**, and removing a *filtered* member's partner is a guarded,
   logged action (as removing a partner is today).
4. Caps of 4 are enforced in the app. The server is a blind relay and can only cap total mailboxes.

### Protocol (reuses what exists: `dns/Pairing`, `dns/Ecies`, blind mailboxes)
- Today's pairing is one shared secret per (owner, partner) pair, giving a mailbox, a token and an encryption key. A group of
  N filtered members needs up to N×(N−1) of those. Doing that by hand is the tedious part, so the group flow automates it:
  1. The creator makes a random **group secret** and shares one invite (secret + server address + role slots) over any
     message app. The group secret derives a **group mailbox** (an ordinary blind mailbox).
  2. Each joiner posts a **hello** (name, role, a public key) sealed with the group key. Members see each other's hello
     and a short **fingerprint** to compare out of band (so a leaked invite can't add a stranger unnoticed).
  3. Each filtered member generates one pairing secret per allowed viewer and posts it **sealed to that viewer's public
     key** into the group mailbox. Viewers pick theirs up and start watching. From then on reports flow over the
     existing per-pair mailboxes.
- **No server change** beyond the caps already there. Server-side group accounting would break the blind-relay property.
- Open protocol questions: invite expiry (the server can't enforce single use of a bearer secret), what happens when a
  member's phone is wiped (their key is gone: they re-join and everyone re-pairs), and ordering of hellos.

### Built 2026-11-23 (none of it run on a phone yet)

- `dns/Group` (rules), `dns/GroupProtocol` (signed replayable log, fingerprint approval, sealed hand-over), `dns/GroupInvite`:
  pure, 29 JVM tests including a simulated 4-phone group.
- `data/GroupStore` (keys, secret, local bookkeeping, decrypted log and outbox in `noBackupFilesDir`), `sync/GroupSync`
  (one pass inside `SyncWorker`, before the pairing loops) and `sync/GroupManager` (what the screen calls: sign, queue, kick),
  `GroupActivity` (start, join, approve, remove, role, who sees me, leave), reached from Partners & reports. Loosening actions go
  through a new friction action, `LEAVE_GROUP`. Every group change is written to the activity log (`partner`/`group_*`), and
  `Digest.alertText` turns them into partner alerts.
- **Known limits, on purpose, to revisit:** (1) **History lives on the server**, which keeps the last 300 messages and 30 days per
  mailbox, so someone joining a long-lived group late would not see its first messages. Members keep their own copy. Join
  within a day or two, then use **Close to new members**. (2) One group per phone. (3) A phone that is reinstalled loses its
  member key and must rejoin as a new member. (4) The group invite is a bearer secret: anyone holding it can join and be
  *seen* (up to 4), but nothing is shared with them until a member approves them by fingerprint.

### Build order (each step ships and is testable alone)
0. **Gate B first:** pair two real phones with today's flow. It has never run on a phone, and groups multiply every bug in it.
1. Roles in the app: Filtered / Partner / both, partner-only mode with no VPN and no upload. Pure rules in `dns/`.
2. Group model and storage (`sync/`), pure state machine in `dns/` (hello, join, leave, remove, caps), JVM-tested.
3. Group invite + hello + sealed pairing exchange; fingerprint confirmation UI.
4. Group view: one screen listing members, role, last check-in, alerts; per-member visibility switches.
5. Leave/remove flows with notifications and friction.
6. Two-phone, then three-phone test on real hardware. Record results in `STATE.md` with 📱 only for what ran.

## 4. Server release and Docker (built 2026-11-23)

- `server/Dockerfile`: multi-stage, non-root, read-only-friendly, health check. **Tested:** builds, healthy, non-root,
  read-only fs, per-client rate limit through the Docker bridge.
- `server/deploy/docker-compose.yml` + `Caddyfile` + `.env.example`: server on `127.0.0.1:8080`, optional `--profile https`
  for automatic HTTPS. **Parsed only**: `docker compose` is not installed on the laptop, so it has not been brought up.
- `FF_TRUSTED_PROXIES` (new): a proxy in a container is not loopback, so which peers may set `X-Forwarded-For` is
  configurable. Without it every client behind a Docker proxy shares one rate-limit bucket.
- `GET /healthz`. 26 server tests.
- `.github/workflows/server-release.yml`: tag `server-vX.Y.Z` → tests → tarball with the compose file → multi-arch image to
  GHCR → GitHub Release. **Not run yet.** The first run publishes a package; as long as the repo is private that is private
  too, and its visibility is @sidewinderzz's setting.
- To do: install the compose plugin on the laptop and bring the stack up end to end; backup/restore test of the data volume;
  decide the image owner (`ghcr.io/<owner>/focusfence-server`).

## 5. Draft decision entry for `DECISIONS.md` (for @flyboy-byte and @sidewinderzz to adopt, change, or reject)

> **D-017 — Accountability is an opt-in mode, and groups are its target**
> *Proposed 2026-11-23, not adopted.*
> Focus Fence stays a local-first, self-control tool by default: no account, no server, no observation. A separate,
> explicitly enabled **accountability mode** adds a local activity log, optional on-device screenshot scoring with a
> permanent visible indicator, device-admin uninstall protection, and **partners**: one or more people (up to a group of
> four) who receive reports and can unlock strict mode. This supersedes D-010, D-012 and the D-016 rejection **only inside
> that mode**, and for the reason that the threat there is a person who has agreed to be watched and may still try to
> evade it; outside the mode all three stand. **Every accountability layer is independently optional** (server, partners,
> text capture, screenshots, AI): with all of them off the app is the same lightweight, local-first blocker it was, and
> the server is a self-hostable component, not a dependency. "Recommended" is guidance in the UI, never a requirement.
> **Consequence:** the accountability code is isolated (`dns/` pure core, `sync/`, `monitor/`, `server/`), never required by
> the filter, and every observation in it is disclosed in the UI and logged.

## 6. Merge-readiness checklist (checkable)

- [ ] Landing option chosen and D-017 adopted or replaced, by both people.
- [ ] Reconciled with `shareddev` (A or C), `./gradlew testDebugUnitTest assembleDebug` and the server tests green **on the merged result**.
- [ ] `LICENSE` (MIT, per D-015) present; dependency and model licences listed (the NSFW model is MIT; check bundled lists).
- [ ] Personal and operational material out of the public tree: `docs/plans/04-backend-deploy.md` (names the VPS and its layout),
      `docs/HANDOFF.md`, owner and device references in `STATE.md`; secrets scan of the whole history (none found in the working tree 2026-11-23).
- [ ] Signing: O-002 decided; the release workflow and secrets match the decision; one release workflow, not two (ours and W5's).
- [ ] README states what the app does in both modes, including the honest limits and the observation disclosures.
- [ ] `CHANGELOG.md` covers 2.2 to current.
- [ ] Settings only @sidewinderzz can change: branch protection on `main` (PR + CI + review), package visibility, Actions secrets, repo visibility.
- [ ] A real two-phone test (and three for groups) recorded in `STATE.md`.

## 7. Landing path: decided by conversation, not by us (2026-11-23)

Logan: "We're the better branch. I'll talk to him." So **this branch stays the base**; no merge work, no `shareddev` changes and
no PR to the upstream repo until Logan and @sidewinderzz have talked. Talking points, so the conversation has facts:

- **What this branch has that `main` and `shareddev` don't:** a working accountability loop (partner reports, alerts, unlock
  codes), a deployed and independently audited server with a Docker release path, on-device screenshot scoring with a flag
  policy tuned on real data, a measured battery history (5 days, 10 versions), 192 app and 26 server tests, and 10 signed releases.
- **What `shareddev` has that this branch lacks:** multi-source lists with provenance, an allowlist, a UI redesign, release
  signing and a draft-release workflow, a benchmark script, the work-order docs. These can be brought over by feature.
- **What only @sidewinderzz can decide or do:** adopt or change D-017, signing custody (O-002) and the release workflow,
  the licence file, branch protection on `main`, package and repo visibility, and whether to merge at all.
- **What would make a merge reviewable:** one landing path (section 2), a D-017, and CI green on the merged result.

## 8. Not decided

Whether this goes upstream at all (A/C) or ships as its own product (B) (Logan will talk to @sidewinderzz); the group defaults other than visibility;
whether the repo goes public; the server image owner; whether the name stays "Focus Fence AC".
