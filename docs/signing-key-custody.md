# Signing key custody — decision support for O-002

> **Tier:** Low-level · **Audience:** @sidewinderzz, making a one-way decision ·
> **Use when:** you are ready to unblock W4 and cut a first release.
>
> **Resolved 2026-11-23 by [D-017](../DECISIONS.md):** the key in the repo's Actions secrets (certificate SHA-256
> `2ca769a5…6098671`) is the release key for every release. The rest of this document is the decision support as it
> stood before that, kept for the reasoning.
>
> ~~**Nothing here has been executed.**~~ No key exists, no keystore was generated, and no password
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

### 3. Where does the backup live?

Offline, encrypted, and **not** in: the repo, a chat, email, a cloud drive that syncs to a laptop,
or a password manager note that is shared with anyone who does not need it. A hardware-encrypted
USB stick or a printed-and-sealed copy of the base64 keystore both work. Two copies in two physical
places beats one copy in one.

Note this repo currently lives under `OneDrive`. Do not generate the keystore inside the working
tree — even gitignored, it would sync.

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

**Recommendation, stated as a recommendation and not a decision:** sign by hand for the first
release. You can always move to CI signing later; you cannot un-leak a key. W5 can publish a
Release with a manually-signed APK perfectly well.

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
- `.gitignore`: `keystore.properties`, `*.jks`, `*.keystore`.
- If CI signs: gate it to pushes on `main` and tags only, **never** `pull_request`.
- O-002 recorded as resolved in `DECISIONS.md` with the answers to 1–4.

## Status

~~**Unresolved.** Awaiting @sidewinderzz on questions 1–4.~~ Resolved by D-017. W4 stays BLOCKED until then, and this
document is not a substitute for that decision.
