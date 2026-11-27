# W4 — Release signing config + CI secret

**Track:** Release · **Depends on:** decision O-002 · **Blocks:** W5
**Status:** 🔴 **BLOCKED — do not start.** Requires a human decision and a key ceremony.

## Why

`./gradlew assembleRelease` currently produces `app/build/outputs/apk/release/app-release-unsigned.apk`.
Verified 2026-08-14:

```
$ apksigner verify --print-certs app-release-unsigned.apk
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
```

**Android will not install this file.** There is no `signingConfig` block in
`app/build.gradle.kts`. Until there is, "a stranger can download and use it" is impossible.

## Why it's blocked — this is a one-way door

The signing key becomes the app's **permanent identity**. Every future update must be signed with
the same key. Sign a later release with a different one and Android refuses the update: every user
must uninstall and reinstall, losing all their rules — in an app whose entire purpose is holding
boundaries people rely on.

Losing the keystore is unrecoverable. Leaking it lets anyone ship a "Focus Fence update" to users
who trust it, on an app that holds VPN and Accessibility permissions.

## The decision (O-002)

Settle **before** generating anything:

1. **Who generates and holds the keystore?** It's @sidewinderzz's project; the default should be him.
2. **Does the other maintainer hold a copy?** Bus factor of one on an unrecoverable secret is a real
   risk. Two copies doubles leak surface. Pick deliberately.
3. **Where does the backup live?** Offline, encrypted, not in the repo, not in a chat.
4. **Does CI sign, or is signing done by hand?** CI signing means the key lives in a GitHub Actions
   secret — convenient, reproducible, and readable by anyone who can push a workflow change to a
   branch that runs on `pull_request`. Manual signing keeps the key off GitHub entirely at the cost
   of a manual step per release.
5. **Key parameters:** RSA 4096, validity ≥ 30 years (a key that expires mid-life strands the app).

Research queue item 9 covers how comparable FOSS Android projects handle this.

## Execution, once decided

### `app/build.gradle.kts`

Add a `signingConfigs.release` block that reads from environment variables or a gitignored
`keystore.properties`, **never** from literals in the file:

```kotlin
// values come from keystore.properties (gitignored) or env vars in CI;
// the build must still succeed unsigned when they are absent, so local
// debug work and forks don't break.
```

Wire it to `buildTypes.release.signingConfig`. **The build must degrade gracefully**: if no key
material is present, fall back to producing the unsigned APK rather than failing. Forks and
contributors must still be able to run `assembleRelease`.

### Repo hygiene

Add to `.gitignore`: `keystore.properties`, `*.jks`, `*.keystore`.

### CI (if CI signs)

Store the keystore base64-encoded in a repository secret plus separate secrets for the store
password, key alias and key password. Decode to a temp file in the workflow, and ensure the step
does not echo it. **Gate signing to pushes on `main` and tags only** — never on `pull_request`,
which can run from forks.

## Acceptance criteria

- [ ] `./gradlew assembleRelease` with key material present produces `app-release.apk`, and
      `apksigner verify --print-certs` reports **verified** with the expected certificate SHA-256.
- [ ] `./gradlew assembleRelease` **without** key material still succeeds, producing an unsigned APK.
- [ ] No keystore, password or alias appears anywhere in git history —
      `git log -p | grep -i "keystore\|storePassword"` shows only config plumbing.
- [ ] `.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties`.
- [ ] CI signing (if chosen) does not run on `pull_request` events.
- [ ] The certificate fingerprint is recorded somewhere durable outside the repo, so a future
      maintainer can verify a release is authentic.
- [ ] O-002 recorded as a resolved decision in `DECISIONS.md` with the custody answers.

## Out of scope

- Publishing releases (W5).
- Reproducible builds and F-Droid (O-005).
- Play Store upload key / Play App Signing — different model, and Play is not the target.
