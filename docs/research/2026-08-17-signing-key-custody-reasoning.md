# R1 — Signing-key custody: the actual decision, not just prior art

**Engine:** Claude (high-reasoning Claude Code session — executed per `docs/reasoning-prompts.md`
R1, not a web-research item; no external facts below, only reasoning over this repo's own docs)
**Date:** 2026-08-17
**Inputs read:** `CLAUDE.md`, `DECISIONS.md` (O-002), `docs/archive/infrastructure.md`,
`docs/archive/work/W4-release-signing.md`, `docs/archive/work/W5-release-workflow.md`,
`docs/signing-key-custody.md`, `docs/research/2026-08-15-signing-key-custody-prior-art-claude.md`
**Feeds:** O-002. **This document is decision support, not a decision.** O-002 stays open until
@sidewinderzz adopts, amends, or rejects the draft text at the bottom. Nothing here authorizes
starting W4.

---

## 1. Failure modes of each pure option, for this specific two-person shape

The project's shape matters more than general best practice: one repo owner who is the **sole
admin** (Logan / @sidewinderzz), one regular contributor with write access, GitHub Releases as the
only distribution channel, CI already running on every push, an app whose permission pair
(VPN + Accessibility) is the most abusable on Android, and a release cadence that will be
*occasional* — this is a hobby project with no server-side component that could ever force an
emergency release on a deadline.

Two properties frame everything below:

- **Leak and loss are asymmetric in time.** A *lost* key orphans the app — bad, but bounded and
  honest (republish under a new identity, users reinstall once). A *leaked* key is worse and
  unbounded: anyone can ship a trusted "Focus Fence update" to devices holding VPN and
  Accessibility grants, and there is no rotation path that doesn't also orphan every user. You can
  recover from loss by paying a known price; you cannot un-leak.
- **A GitHub Actions secret is write-only.** You can never read it back through the UI or API; the
  only way to get a keystore *out* of a secret is to push a workflow that exfiltrates it. So "the
  CI secret" can never be the backup — a subtlety that quietly turns "CI signs" into "CI signs and
  someone still has to solve custody anyway."

### Option A — CI signs (keystore as a GitHub Actions secret)

Walking the four scenarios from the prompt:

1. **Logan becomes unavailable.** Releases keep flowing — the contributor tags, CI signs. This is
   the one genuine advantage of Option A, and it is real. But it is shallower than it looks: the
   contributor cannot *manage* the secret (rotation, re-upload, fixing a broken workflow's
   permissions all need admin), so the arrangement survives Logan's absence only until the first
   time the secret machinery needs touching. And if the secret was ever the only copy of the
   keystore, recovery means deliberately writing an exfiltration workflow — a horrifying procedure
   to depend on, and one that itself requires push access to still exist.
2. **GitHub account/org compromised.** This is where Option A is catastrophic. The attacker
   doesn't just deface the repo — they hold the app's permanent identity. They can cut a genuinely
   signed release; every existing user's device will accept it as a legitimate update, and it
   arrives holding VPN and Accessibility permissions. Because rotation orphans users, there is no
   clean way back. The blast radius of "GitHub account compromised" grows from "repository
   integrity" to "every installed device," permanently.
3. **Contributor needs to release, Logan unreachable.** Works (same as scenario 1). Worth pricing
   honestly: for this app the urgent-release case is a security bug in the app itself — real but
   rare, and a days-to-weeks delay is survivable for an offline tool with no server dependency.
4. **Admin rights sit with one person.** The security of a CI-held key is exactly the security of
   the repo's workflow-trigger and branch-protection configuration — which only Logan can
   maintain. The contributor's write access means a compromised *contributor* account can push a
   workflow change that exfiltrates the secret (unless protections are configured exactly right,
   forever). So Option A doubles the number of accounts whose compromise leaks the key, while
   leaving only one person able to maintain the machinery that prevents it. That asymmetry is the
   quiet flaw: the person most able to leak it by accident is not the person able to configure the
   guardrails.

Also worth naming: prior art is unanimous against this. Research Q9 found **zero of four**
comparable projects put the production key in CI, and the one project whose CI signs anything
(RethinkDNS) deliberately gives CI a *different key for a different `applicationId`*.

### Option B — Logan signs by hand; the key never touches GitHub

1. **Logan becomes unavailable.** In the pure form — no escrow — the app can never update again.
   This is the prior-art norm (Q9 found no comparison project documents any succession practice)
   and it is the *bad* part of the norm: an undocumented bus factor of 1 is how Android apps get
   orphaned. Option B without escrow fails this scenario outright. **This is the scenario the
   hybrid in §2 exists to fix.**
2. **GitHub account/org compromised.** The identity survives. An attacker can attach a malicious
   APK to a Release, but it is signed with *some other key*, so every existing install refuses it
   as an update — Android's signature permanence, the very property that makes this decision a
   one-way door, acts as the defense. Damage is bounded to new installs during the compromise
   window, the malicious release can be deleted, and the project continues under the same
   identity. Compare scenario 2 under Option A: this is the single largest difference between the
   options, and it falls entirely on Option B's side.
3. **Contributor needs to release, Logan unreachable.** Blocked until Logan returns. Priced above:
   annoying, not fatal, for an app with no server-side dependency and occasional cadence.
4. **Admin-rights asymmetry.** Mostly irrelevant — there is no CI secret machinery to misconfigure,
   nothing for a compromised contributor account to exfiltrate. The key's security reduces to the
   security of Logan's machine and the backups, which is a smaller and better-understood surface.

### The asymmetry that decides it

Option A's genuine benefit (releases continue without Logan) mitigates an *inconvenience* —
delayed releases for an offline hobby app. Option A's cost is exposure to the *unrecoverable*
failure (leak via either GitHub account or a misconfigured trigger). Option B's cost is an
inconvenience plus one real risk (orphaning on Logan's permanent unavailability) — and that risk
is fixable with escrow, below, without touching GitHub at all. Trading a fixable inconvenience for
an unfixable catastrophe is the wrong direction; prior art unanimously agrees.

## 2. The hybrid that is actually better than either pure option

The R1 prompt asks whether "CI signs but the raw keystore is also escrowed offline" beats both
pure options. It doesn't — that variant keeps Option A's scenario-2 catastrophe and merely patches
its backup problem. The hybrid that *does* beat both pure options inverts it:

**Logan signs by hand, and custody is split so that loss and unavailability are both covered
without creating a second live copy:**

- **Logan holds the only live keystore**, on his machine, generated and stored *outside* the
  OneDrive-synced tree (the custody doc's warning stands: the repo's working tree syncs, so
  nothing key-shaped may live in or near it). Signing credentials go in
  `~/.gradle/gradle.properties` (AdAway's pattern — outside the repo, can't be committed or
  synced), not a project-root `keystore.properties`.
- **The contributor holds a sealed, passphrase-encrypted copy** of the keystore (e.g.
  `gpg --symmetric` over the `.jks` with a strong generated passphrase — *not* merely the JKS
  store password, which is offline-crackable if weak). The contributor cannot use it: no
  passphrase. Leak surface is not doubled — compromising the contributor's copy alone yields
  ciphertext.
- **The passphrase exists on paper, in two sealed physical locations Logan controls** (with the
  cert fingerprint and key alias written alongside). One of the two should be a place the
  contributor could reach in a genuine "Logan is permanently gone" event — a sealed envelope with
  a person or place both trust. That single sheet of paper is what converts the bus factor from 1
  to "1 for shipping, 2 for survival," which is the correct shape: day-to-day only Logan can sign,
  but Logan's permanent absence no longer orphans the app.
- **CI never holds the production key.** If nightly/test-channel convenience is ever wanted, use
  the RethinkDNS pattern *as designed*: a different key, for a different `applicationIdSuffix`
  (".alpha"), in a workflow triggered only by `workflow_dispatch`/`schedule` — never
  `pull_request`. That composes with everything above and can be added years later without
  touching the production key.

Why this beats "two live copies": it fixes the bus factor without doubling the number of machines
where a usable key sits. Why it beats "one copy, no escrow": scenario B-1 stops being fatal. Why
it beats CI-signs-plus-escrow: it keeps the GitHub-compromise blast radius at "repository," not
"every installed device."

## 3. Concrete recommendation (specific, as the prompt demands)

- **Q1 — who generates and holds:** Logan. Generated on his machine, outside any synced directory.
- **Q2 — does the contributor hold a copy:** yes, but *sealed* — passphrase-encrypted, unusable
  alone. No second live copy exists.
- **Q3 — where the backup lives:** two encrypted copies in two physical places (hardware-encrypted
  USB stick + a second stick or printed base64), plus the paper passphrase in two sealed physical
  locations, one reachable by the contributor in a defined emergency. Nothing in the repo, a chat,
  email, or anything that syncs. **Every backup is verified the day it is made** by actually
  restoring it and signing a scratch APK from the restored copy — an unverified backup is a hope,
  not a backup.
- **Q4 — CI or hand:** hand. CI builds and tests the release APK unsigned; Logan signs with
  `apksigner` and attaches. Revisit only via the separate-alpha-key pattern, never by giving CI
  the production key.
- **Key parameters:** RSA 4096, validity 10950 days (30 years), single alias, per the custody doc.
- **Day-of-generation ceremony**, in order: generate → record the SHA-256 certificate fingerprint
  (publish it in the README and every release body — fingerprints are public information and are
  how a stranger verifies a release) → make both encrypted backups → **verify both by
  restore-and-sign** → hand the contributor their sealed copy → seal the two passphrase envelopes
  → add the signing config and `.gitignore` entries (that part is W4's work, not the ceremony's).

### Consequence for W5 that nobody has written down yet

`W5-release-workflow.md` currently specifies "tag → `assembleRelease` (**signed**, per W4) →
create Release" — that line silently assumed CI signing. Under this recommendation W5's shape
changes: tag → CI runs tests and builds the *unsigned* release APK + `mapping.txt` and creates a
**draft** Release → Logan signs locally, attaches the signed APK, puts its SHA-256 in the body,
publishes. The SHA-256 must be computed from the *signed* artifact (signing changes the bytes), so
CI cannot pre-fill it. W5 should be amended when it unblocks; noted here rather than edited there
because W5 is blocked and out of R1's scope.

---

## 4. Draft resolution text for O-002 — paste-able, pending @sidewinderzz's adoption

> The text below is written in `DECISIONS.md` house style so it can be pasted as the O-002
> resolution **if and when @sidewinderzz decides**. Until then it decides nothing.

```markdown
## O-002 — resolved: hand-signing with split custody

*Decided: <date>, by @sidewinderzz.*

The release keystore is generated and held by @sidewinderzz alone, on his machine, outside any
synced directory. Releases are signed by hand; **the production key never enters CI or any GitHub
secret.** Credentials live in `~/.gradle/gradle.properties`, never in the project tree.

Custody is split rather than duplicated: the contributor holds a passphrase-encrypted copy of the
keystore that is unusable alone; the passphrase exists on paper in two sealed physical locations,
one reachable by the contributor in a genuine permanent-unavailability event. Two encrypted
backups live in two physical places, each verified by restore-and-sign on the day it was made.
The certificate's SHA-256 fingerprint is public — README and every release body.

**Why:** leak and loss are asymmetric. A lost key orphans the app once; a leaked key lets anyone
ship a trusted update to devices holding VPN + Accessibility, forever, with no rotation path.
CI signing would tie the key's fate to the GitHub account and to workflow-trigger configuration
only one admin can maintain, in exchange for mitigating a mere inconvenience (releases wait for
@sidewinderzz). Hand-signing keeps a GitHub compromise's blast radius at "repository," not "every
installed device" — signature permanence itself becomes the defense. Prior art is unanimous
(research Q9: 4/4 comparable projects hand-sign production; the one CI-signing exception uses a
separate key for a separate applicationId). The split-custody escrow fixes hand-signing's one
real failure mode — orphaning on permanent unavailability — without creating a second live copy.

**Key parameters:** RSA 4096, 30-year validity.

**Consequence:** W4 proceeds (signing config reading from `~/.gradle/gradle.properties`, graceful
unsigned fallback, `.gitignore` for `*.jks`/`keystore.properties`). W5's workflow is amended:
CI builds unsigned + drafts the Release; the signed APK and its SHA-256 are attached by hand.
If a nightly channel is ever wanted, it gets its own key under a `.alpha` applicationIdSuffix
(RethinkDNS pattern) — never the production key.

**Reopens if:** the project gains maintainers or release cadence that makes hand-signing a real
bottleneck — and even then, the answer under review is the separate-alpha-key pattern, not the
production key in CI.
```

## Status

O-002 remains **open**. The ball stays with @sidewinderzz: adopt the draft above (filling the
date), amend it, or reject it. The three genuinely personal choices embedded in it — that the
contributor holds a sealed copy, where the two physical locations are, and who/where the
contributor-reachable envelope sits — are recommendations with reasoning, not defaults to
silently inherit.
