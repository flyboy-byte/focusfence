# Signing key custody — decision support for O-002

> **Tier:** Low-level · **Audience:** @sidewinderzz, making a one-way decision ·
> **Use when:** you are ready to unblock W4 and cut a first release.
>
> **Nothing here has been executed.** No key exists, no keystore was generated, and no password
> was chosen. This document exists so the decision can be made deliberately rather than in the
> middle of a release. Every command below is for **you** to run — the key and its passwords must
> never pass through an agent session, a chat log, or this repository.

## Why this blocks everything

`./gradlew assembleRelease` produces `app-release-unsigned.apk`. Android will not install it.
Until a `signingConfig` exists, "a stranger can download and use this" is impossible, so W4 blocks
W5, which blocks the first release, which is the gate on FRAMEWORK.md Phase 4.

It is blocked on a *decision*, not on work. The work is roughly an hour.

## What makes it one-way

The signing key becomes the app's **permanent identity**:

- Every future update must be signed with the same key. Sign a later release with a different one
  and Android refuses the update outright — every user must uninstall and reinstall, losing all
  their rules. In an app whose entire purpose is holding boundaries people rely on, that is a
  uniquely bad failure.
- **Losing the keystore is unrecoverable.** There is no reset, no support channel, no recovery.
  The app is orphaned and must be republished under a new identity.
- **Leaking the keystore** lets anyone ship a "Focus Fence update" to people who trust it — on an
  app that holds VPN and Accessibility permissions. That is close to worst-case for a
  security-adjacent app.

Those three facts are why this is not a "just generate one and move on" step.

It is not a hypothetical, either. NetGuard ships this exact caveat to its users as standing FAQ
text — *"Because F-Droid builds and GitHub releases are signed differently, an F-Droid build needs
to be uninstalled first to be able to update to a GitHub release"* ([FAQ #15](https://raw.githubusercontent.com/M66B/NetGuard/master/FAQ.md)).
A comparable app, same category, permanently explaining to users that they must uninstall and lose
their state to change channels.

## A fifth question the research surfaced: does F-Droid become a second key holder?

*Added 2026-08-15 from research Q9. Not one of the original four, but it is decided by the same
one-way door and is cheaper to settle now than later.*

By default **F-Droid generates and holds its own signing key per app** and signs its builds with
that, not with the developer's key ([Security Model](https://f-droid.org/docs/Security_Model/)).
Confirmed concretely: AdAway's F-Droid metadata has neither `AllowedAPKSigningKeys` nor
`Binaries`, so F-Droid signs AdAway with F-Droid's key.

The escape hatch is **reproducible builds** — if F-Droid can rebuild byte-identically, it ships
the *developer-signed* APK instead, recording the developer certificate hash as
`AllowedAPKSigningKeys` ([reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)).

So if Focus Fence ever lands on F-Droid without reproducible builds, there are two Focus Fence
identities in the world and no migration path between them that preserves a user's rules — the
NetGuard situation above, imported wholesale. Since O-005 / research Q5 already contemplate
F-Droid, deciding to aim for reproducible builds *before* the first signed APK exists is much
cheaper than retrofitting it once users exist on both channels. This does not need answering to
unblock W4; it needs answering before anyone submits to F-Droid.

## The four questions

### 1. Who generates and holds it?

It is @sidewinderzz's project, so the default is @sidewinderzz. The person who holds the key is the
person who can ship — that is a governance decision as much as a technical one.

### 2. Does the other maintainer hold a copy?

A genuine tension, and worth deciding rather than defaulting:

| | Bus factor | Leak surface |
|---|---|---|
| One holder | **1** — if that person is unavailable, the app cannot be updated, ever | Smallest |
| Two holders | 2 | Doubled |

For a two-maintainer hobby project, "one holder plus a sealed offline backup" is usually the better
shape than "two live copies" — it fixes the bus factor without doubling the number of live secrets.

**Prior art gives no help here — confirmed 2026-08-15.** Research Q9 looked specifically for
documented backup, escrow or succession practice across all four comparison projects and found
**none**: no split custody, no hardware-token signing, no offline-backup procedure, no "if I
become unavailable" plan, anywhere public. Read that as "there is no convention to copy," not as
"the question does not matter" — an undocumented bus factor of 1 is the normal state of a
single-maintainer Android project and a frequently-realised way they get orphaned. Simply having
written this question down puts the project ahead of the comparison set.

### 3. Where does the backup live?

Offline, encrypted, and **not** in: the repo, a chat, email, a cloud drive that syncs to a laptop,
or a password manager note that is shared with anyone who does not need it. A hardware-encrypted
USB stick or a printed-and-sealed copy of the base64 keystore both work. Two copies in two physical
places beats one copy in one.

Note this repo currently lives under `OneDrive`. Do not generate the keystore inside the working
tree — even gitignored, it would sync.

**That argument extends to the credentials file, not just the keystore — confirmed 2026-08-15.**
RethinkDNS and NetGuard both read a `keystore.properties` from the *project root*; AdAway instead
reads its four signing properties from `~/.gradle/gradle.properties`, in the maintainer's home
directory and entirely outside the repo. AdAway's placement is strictly safer for the same reason
the OneDrive warning exists: a file outside the working tree cannot be accidentally committed,
cannot be caught by an over-broad `git add -A`, and cannot be swept into a sync client that is
watching the project directory. Given where this repo lives, prefer AdAway's location over the
project-root convention when W4 is eventually written.

### 4. Does CI sign, or do you sign by hand?

This is the real choice.

**CI signs.** The keystore lives base64-encoded in a GitHub Actions secret.
- Convenient, reproducible, no manual step per release.
- But: anyone who can push a workflow change to a branch that runs on `pull_request` can exfiltrate
  it. That includes fork PRs unless gating is exactly right. The key is only as protected as the
  weakest workflow trigger in the repo, forever.

**You sign by hand.** The key never touches GitHub.
- One manual step per release: download the unsigned APK, `apksigner sign`, upload to the Release.
- Materially smaller attack surface. For a project that ships rarely, the manual step costs little.

#### What comparable projects actually do — confirmed 2026-08-15

This section previously reasoned from first principles. It no longer has to: research Q9 read the
build files and CI workflows of RethinkDNS, NetGuard, personalDNSfilter and AdAway directly.
Full findings and citations: [`research/2026-08-15-signing-key-custody-prior-art-claude.md`](research/2026-08-15-signing-key-custody-prior-art-claude.md).

**Four out of four sign the production artifact by hand.** None of them puts the key that signs
the app users install into CI:

| Project | Production key in CI? | Credentials read from |
|---|---|---|
| RethinkDNS | No | `keystore.properties` (project root) |
| NetGuard | No — no CI exists at all | `keystore.properties` (project root) |
| AdAway | No — CI builds `assembleRelease` **unsigned** | `~/.gradle/gradle.properties` (outside the repo) |
| personalDNSfilter | No — no CI exists at all | no `signingConfigs` in the build at all |

**A third option this document did not previously consider.** RethinkDNS is the only one whose CI
signs anything, and reading `app/build.gradle` shows why that is not a counter-example: it keeps
*two* signing configs. Production reads `keystore.properties` locally; a separate `alpha` config
reads only environment variables, and the alpha build type carries `applicationIdSuffix ".alpha"`.
The CI-held key therefore signs **a different Android app identity** — leaking it cannot produce
an update to the real app. Their alpha workflow is also triggered by `workflow_dispatch` and a
monthly `schedule` only, never `pull_request`, closing the exfiltration path described above.

So if CI convenience is ever wanted, the move is *not* "give CI the production key with careful
trigger gating" — it is "give CI a different key for a different `applicationId`." That composes
with hand-signing rather than replacing it, and it can be added later without touching the
production key.

**Recommendation, stated as a recommendation and not a decision:** sign by hand for the first
release. You can always move to CI signing later; you cannot un-leak a key. W5 can publish a
Release with a manually-signed APK perfectly well. Prior art now supports this unanimously rather
than merely permitting it.

### 5. Key parameters

Whatever else is decided: **RSA 4096**, validity **≥ 30 years**. A key that expires mid-life
strands the app exactly as badly as losing it.

## What you would run

Only once questions 1–4 are answered. Run this somewhere outside the OneDrive tree.

```bash
keytool -genkeypair -v -keystore focusfence-release.jks -keyalg RSA -keysize 4096 -validity 10950 -alias focusfence
```

Then record the fingerprint somewhere durable and **outside this repo**, so a future maintainer can
verify a release is authentic:

```bash
keytool -list -v -keystore focusfence-release.jks -alias focusfence
```

## What still needs writing once decided

Tracked so the decision is not mistaken for the work:

- `signingConfigs.release` in `app/build.gradle.kts`, reading from env vars or a gitignored
  `keystore.properties` — never literals. **The build must still succeed unsigned when key material
  is absent**, so forks and contributors can run `assembleRelease`.
- `.gitignore`: `keystore.properties`, `*.jks`, `*.keystore`. **Verified 2026-08-15:** the current
  `.gitignore` covers `*.keystore` (with `!debug.keystore`) but **not** `*.jks` and **not**
  `keystore.properties`. Note the `keytool` command below produces `focusfence-release.jks` — a
  `.jks`, which today is *not* ignored. Left unfixed deliberately: it belongs to W4, which is
  BLOCKED, and doing it here would be scope creep under a research item.
- If CI signs: gate it to pushes on `main` and tags only, **never** `pull_request`.
- O-002 recorded as resolved in `DECISIONS.md` with the answers to 1–4.

## Status

**Unresolved.** Awaiting @sidewinderzz on questions 1–4. W4 stays BLOCKED until then, and this
document is not a substitute for that decision.

**Research Q9 landed 2026-08-15** ([raw findings](research/2026-08-15-signing-key-custody-prior-art-claude.md)),
which changes what is still open:

- **Question 4 (CI vs hand-signing)** now has unanimous prior art behind hand-signing, plus a
  third option (RethinkDNS's separate-key-for-a-separate-`applicationId` pattern) that was not on
  the table before. This is as decided as prior art can make it; it still needs a human to say so.
- **Questions 1–3 (who holds it, does the second maintainer hold a copy, where the backup lives)**
  got **no** guidance — no comparison project documents any custody or escrow practice at all.
  These remain genuine blank-page decisions and research cannot help further. Nothing short of
  @sidewinderzz answering them will move this.

The research was explicitly *decision support*, per its own framing: it does not resolve O-002 and
no part of it should be read as having done so.

**Reasoning item R1 landed 2026-08-17**
([full analysis](research/2026-08-17-signing-key-custody-reasoning.md)): a failure-mode walk of
CI-signing vs hand-signing for this project's specific two-person/one-admin shape, a split-custody
hybrid (one live key with @sidewinderzz; a sealed passphrase-encrypted copy with the contributor;
the passphrase on paper in two sealed physical locations), and **draft resolution text for O-002
in `DECISIONS.md` house style, ready to paste if adopted**. It also flags a W5 consequence: that
work order's "assembleRelease (signed)" line silently assumed CI signing and needs amending under
a hand-signing decision. Same discipline as Q9: decision support only — O-002 remains open until
@sidewinderzz decides.
