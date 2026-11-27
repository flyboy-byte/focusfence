# W2 — CI builds and verifies the release APK

**Track:** Release · **Depends on:** nothing · **Blocks:** W5 · **Status:** Ready
**Size:** ~20 minutes. CI configuration only — no app code.

## Why

`app/build.gradle.kts` sets `isMinifyEnabled = true` for release, and `proguard-rules.pro` is a
single line claiming "No special rules needed; the app has no reflection-based dependencies."

That claim is currently **true** — `assembleRelease` was run successfully on 2026-08-14 (1m59s,
R8 completed, no rules added). But CI has only ever run `assembleDebug`, so nothing watches it. The
first dependency that uses reflection, or the first ProGuard-visible change, breaks the release
build silently and nobody finds out until release day.

R8 also strips code that only fails at *runtime*, which no build check catches. This work order
closes the build half; the runtime half needs a device install (tracked in `FRAMEWORK.md` Phase 2).

## Current state

`.github/workflows/android.yml` runs, on every push and PR:

1. `./gradlew testDebugUnitTest`
2. `./gradlew assembleDebug`
3. Uploads `app/build/outputs/apk/debug/app-debug.apk` as artifact `focus-fence-debug-apk`

Java is temurin 17 via `actions/setup-java@v4`; Gradle via `gradle/actions/setup-gradle@v4`.

## Changes

All in `.github/workflows/android.yml`.

### 1. Add a release build step

After the existing `assembleDebug` step, add `./gradlew assembleRelease`.

Note the output is **`app/build/outputs/apk/release/app-release-unsigned.apk`** — unsigned, because
there is no `signingConfig` yet (that's W4). The filename will change to `app-release.apk` once W4
lands, so the artifact step below must not hard-code the unsigned name in a way that breaks then;
use a glob (`app/build/outputs/apk/release/*.apk`).

### 2. Upload the release APK as a separate artifact

Name it `focus-fence-release-apk`. Keep the debug artifact as-is — the debug APK stays the
convenient sideload for the two maintainers until W5 exists.

### 3. Fail loudly if R8 strips something detectable

Add a step after `assembleRelease` that fails the job if the R8 mapping output is missing, which is
a cheap signal that minification silently didn't run:

```yaml
- name: Verify R8 ran
  run: test -f app/build/outputs/mapping/release/mapping.txt
```

### 4. Upload the R8 mapping file

Artifact `focus-fence-release-mapping`, path `app/build/outputs/mapping/release/`. Without the
mapping file, a stack trace from a released build is unreadable. This becomes important the moment
W5 exists and strangers report crashes.

## Acceptance criteria

- [ ] `.github/workflows/android.yml` runs `assembleRelease` on every push.
- [ ] The workflow fails if `mapping.txt` is absent.
- [ ] Three artifacts upload: `focus-fence-debug-apk`, `focus-fence-release-apk`,
      `focus-fence-release-mapping`.
- [ ] The release APK path uses a glob, so it still resolves after W4 renames the output.
- [ ] `git diff --stat` shows changes **only** in `.github/workflows/android.yml`.
- [ ] The workflow run for this PR is green, and its logs show `:app:minifyReleaseWithR8` executing.
- [ ] Download `focus-fence-release-apk` from the run and confirm it is non-empty (~2.2 MB).

## Out of scope

- Signing (W4). The APK will be unsigned and uninstallable; that's expected here.
- Creating GitHub Releases (W5).
- Adding `androidTest` / instrumented tests to CI — there are none, and adding an emulator to CI is
  a separate decision with real runtime cost.
- Changing `proguard-rules.pro`. Don't add rules speculatively; if R8 breaks, the failure will say
  what it needs.
- Caching or build-speed tuning.
