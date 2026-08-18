# W5 — Tag → signed APK → GitHub Release, plus `CHANGELOG.md`

**Track:** Release · **Depends on:** W2, W4 (and W3 for the license line) · **Blocks:** shipping
**Status:** Blocked by W4 · **Size:** ~half a day

## Why

This is the work order that makes "finished" true (D-006): a signed APK attached to a public
GitHub Release that a stranger can download, verify and install.

There are currently no tags and no releases in the repo.

## Changes

### 1. `.github/workflows/release.yml` (new)

Separate from `android.yml`. Triggers on tags matching `v*`.

Steps: checkout → temurin 17 → `testDebugUnitTest` → `assembleRelease` (signed, per W4) →
compute SHA-256 → create a GitHub Release from the tag, attaching the APK and the mapping file.

Requirements:

- **Tests must run and pass before the release is created.** A tagged build that skips tests is how
  a broken release ships.
- Attach `app/build/outputs/mapping/release/mapping.txt` to the release. Without it, crash reports
  from strangers are unreadable.
- Publish the APK's SHA-256 in the release body, not only as a file.
- Use the tag name as the release name; do not auto-generate a body from commits alone — pull the
  matching section from `CHANGELOG.md`.

### 2. `CHANGELOG.md` (new)

Keep-a-Changelog format, newest first. Backfill from the existing 11 commits — the history is small
and reads clearly:

- `2.1.0` — UI rebuilt on a fixed dark theme with bundled IBM Plex; live Home hero (counter,
  20-minute activity meter, up next, tiles); grouped blocklist cards; stats week chart
- `2.0.1` — battery: cacheable block answers, upstream and event tuning; unreachable app blocking fix
- `2.0.0` — UI restructured into tabs, grouped blocklist, domain wizard
- `1.x` — time budgets fixed against third-party app traffic, VPN exclusions, scroll fix, battery
  fix (blocking tun reads, scoped accessibility events), strict mode, whole-app blocking, stats,
  onboarding, section rules, per-domain daily limits, initial release

Do not invent dates — take them from `git log --date=short`.

### 3. README — install and verify section

Strangers need to know how to install and how to check they got the real thing:

- Download the APK from Releases.
- Verify the SHA-256 against the release body.
- Enable "install unknown apps" for their browser/file manager, and what that means.
- Note the minimum Android version (8.0 / API 26).

### 4. README — permissions and trust section

**This is the most important part of this work order and the easiest to skimp.**

Focus Fence requests `VpnService` **and** `AccessibilityService` — the most abusable permission
pair on Android, and exactly what malware wants. A stranger sideloading an APK is right to be
suspicious, and "it's open source" does not clear it on its own, because almost nobody reads the
source and the APK is not obviously derived from it.

State plainly, for each permission: what it is used for, what it can technically see, and what the
app deliberately does not do with it.

- **VPN:** routes only DNS packets addressed to two synthetic local addresses; all other traffic
  takes the normal network path and never enters the app. No traffic is sent to any server operated
  by this project. Allowed DNS queries go to 1.1.1.1 / 8.8.8.8.
- **Accessibility:** reads the address bar of known browsers to enforce per-path rules, and detects
  when a blocked app comes to the foreground. It does not read page content, form fields or
  passwords, and it is only registered for the specific packages the user has configured.

Also state that releases are built by CI from the public commit, so the binary is traceable to
readable source.

## Acceptance criteria

- [ ] Pushing tag `v2.1.0` produces a GitHub Release with a **signed** APK attached.
- [ ] The release body contains the APK's SHA-256, and it matches
      `sha256sum` of the downloaded file.
- [ ] `mapping.txt` is attached.
- [ ] The workflow fails and creates **no** release if `testDebugUnitTest` fails.
- [ ] `CHANGELOG.md` covers every existing version with accurate dates from git history.
- [ ] README has both an install/verify section and a permissions/trust section.
- [ ] The downloaded APK installs on a real device running Android 8.0+ and the app launches.
- [ ] `apksigner verify --print-certs` on the released APK matches the fingerprint recorded in W4.

## Out of scope

- F-Droid submission (O-005) and reproducible builds.
- Play Store.
- Automated changelog generation from commits.
- In-app update checking — that would mean the app phoning out, which the project's privacy stance
  rejects.
