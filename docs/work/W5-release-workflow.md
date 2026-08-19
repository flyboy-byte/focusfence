# W5 — Tag → signed APK → GitHub Release, plus `CHANGELOG.md`

**Track:** Release · **Depends on:** W2, W4 (and W3 for the license line) · **Blocks:** shipping
**Status:** ✅ Done (`logandev`, 2026-08-18), amended for hand-signing per R1 · **Size:** ~half a day

**Amendment, 2026-08-18:** this work order originally assumed CI holds the signing key
(`assembleRelease (signed, per W4)` as a CI step). O-002 resolved to **hand-signing** — the key
never touches GitHub, per `DECISIONS.md`. So `release.yml` below does **not** produce a signed
APK; it validates (tests + unsigned build) on a tag push and drafts a release skeleton.
**@sidewinderzz signs locally and finishes the release himself** — see `RELEASING.md` for the
exact steps. This is the correct shape given the hand-signing decision, not a shortcut around it.

## Why

This is the work order that makes "finished" true (D-006): a signed APK attached to a public
GitHub Release that a stranger can download, verify and install.

There are currently no tags and no releases in the repo.

## Changes

### 1. `.github/workflows/release.yml` (new)

Separate from `android.yml`. Triggers on tags matching `v*`. **Never has key material — that's the
whole point of hand-signing.**

Steps: checkout → temurin 17 → `testDebugUnitTest` → `assembleRelease` (unsigned; CI has no key) →
create a **draft** GitHub Release from the tag, body pulled from `CHANGELOG.md`, with the unsigned
APK and mapping file attached as placeholders explicitly labelled "unsigned — replace before
publishing."

Requirements:

- **Tests must run and pass before the draft is created.** A tagged build that skips tests is how
  a broken release ships, signed or not.
- Attach `app/build/outputs/mapping/release/mapping.txt` to the draft. Without it, crash reports
  from strangers are unreadable, and it saves the human a step.
- Use the tag name as the release name; do not auto-generate a body from commits alone — pull the
  matching section from `CHANGELOG.md`.
- **The workflow never creates a published release, only a draft.** Publishing (with the real
  signed APK swapped in, and the SHA-256 of *that* file in the body) is `RELEASING.md`'s job, done
  by whoever holds the key.

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

- [x] Pushing a tag `v*` produces a **draft** GitHub Release with an unsigned APK and mapping file
      attached, body pulled from `CHANGELOG.md`. (Verified by reading the workflow logic; actually
      pushing a tag is a human step, not exercised by this session.)
- [ ] `RELEASING.md` documents signing the APK by hand and republishing it, with the SHA-256 of the
      **signed** file in the release body, matching `sha256sum` of the downloaded file. **This
      criterion needs @sidewinderzz to actually run it once, with the real key, to fully close.**
- [x] `mapping.txt` is attached to the draft.
- [x] The workflow fails and creates **no** draft if `testDebugUnitTest` fails.
- [x] `CHANGELOG.md` covers every existing version with accurate dates from git history.
- [x] README has both an install/verify section and a permissions/trust section.
- [ ] The downloaded, signed APK installs on a real device running Android 8.0+ and the app
      launches — needs a real signed build, so it's a human step once O-002's key exists.
- [ ] `apksigner verify --print-certs` on the released APK matches the fingerprint recorded when
      the key was generated — same caveat, needs the real key.

## Out of scope

- F-Droid submission (O-005) and reproducible builds.
- Play Store.
- Automated changelog generation from commits.
- In-app update checking — that would mean the app phoning out, which the project's privacy stance
  rejects.
