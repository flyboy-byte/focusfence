# Plan 11: F-Droid (scoped 2026-11-25, not scheduled)

**Status:** scoped only. Logan, 2026-11-25: "Scope it fully. We're def not doing it anytime soon." Nothing here is
built. Re-check every fact marked *(check)* before starting: F-Droid's policy, the app's code and this repo all move.

**Why it's written down now:** so a later session can start without re-deriving it. The F-Droid skill
(`flyboy-byte/toolbox`, `skills/fdroid/`) holds the general process; this file holds only what is specific to Focus
Fence. disc-tracker (Expo, merged 2026-11-25) and drag-tree (on F-Droid, reproducible) are the worked examples.

## Verdict

**Medium-sized, and no part of it blocks.** The build is the easy kind: plain Gradle (AGP 8.5.2, Kotlin 1.9.24,
Gradle 8.9, JDK 17), only `google()` and `mavenCentral()`, no Play Services or Firebase, R8 off, no Expo step. The
work is in policy: the self-updater, the store text, the app ID and signing with our own key.

Estimate: about 2–3 days of work plus the first two-run release, then review time. That's typically about two weeks of
back-and-forth plus a queue; disc-tracker took about a month. Never promise a date.

## Decisions to make first (Logan's)

| # | Question | Options | Default if unasked |
|---|---|---|---|
| D1 | **App ID.** `app.focusfence.accountable` reads as the domain `focusfence.app`. That domain is registered (Cloudflare nameservers, seen 2026-11-25); who owns it is unknown. F-Droid rejects an ID that matches someone else's domain. | (a) keep it if Logan or @sidewinderzz owns `focusfence.app`; (b) a new ID for every build, e.g. `com.flyboybyte.focusfence`, which current users can only reach by reinstalling (they lose settings, enrollment and logs unless we add export/import); (c) a new ID for the F-Droid build only, so the GitHub and F-Droid builds can't update each other | ask; (a) if owned |
| D2 | **Updater in the F-Droid build.** | (a) an `fdroid` product flavour with no updater code paths, no install permission and no GitHub checks; (b) one APK for both, with update checks and installs off until the person opts in, plus a line saying it bypasses F-Droid's checks | (a) |
| D3 | **Signing.** | (a) reproducible: F-Droid verifies its build matches an APK we sign, and ships ours, so one key covers GitHub and F-Droid; (b) F-Droid's own key, which is simpler but permanent: the two builds can never update each other | (a). Never drop to (b) to get past a mismatch without saying it's permanent (skill rule 11) |
| D4 | **ABIs.** The release APK is 40 MB; the four `libtensorflowlite_jni.so` copies are about 16 MB of that. | (a) one universal APK, as now; (b) per-ABI splits (arm64-v8a, armeabi-v7a, maybe x86_64), each with its own `binary:` URL and version code | (a) first; (b) only if a reviewer asks |
| D5 | **Anti-features we accept.** | See "Review risks" | none expected |

## Work, by area

### W1 — The `fdroid` flavour (D2 = a). The main code change, about a day.

D-029 already says the updater "must be off in that build".

- `app/build.gradle.kts`:
  - add `flavorDimensions += "store"` with `productFlavors { create("github"); create("fdroid") }`;
  - give `fdroid` `buildConfigField("boolean", "SELF_UPDATE", "false")`, and `github` `"true"`;
  - `buildFeatures { buildConfig = true }`.
  - Keep the same `applicationId` in both, unless D1 says otherwise.
  - The default task name changes (`assembleGithubRelease`). Update `release.yml`, `android.yml`, `AGENTS.md` and
    `CLAUDE.md` commands in the same PR.
- **Manifest.** Move `REQUEST_INSTALL_PACKAGES` and the `UpdateActivity` declaration into
  `app/src/github/AndroidManifest.xml`, so the merged `fdroid` manifest has neither.
  - The `FileProvider` stays: `DiagnosticsActivity` shares log exports through it.
- **Call sites to gate** on `BuildConfig.SELF_UPDATE` (as of 2.25.0):
  - `FocusFenceApp.kt:23`: `Updates.schedule` (the WorkManager backstop), and `:48`, the launch check;
  - `Monitor.kt:80`: the screen-on check;
  - `HomeFragment.kt:149–158`, `:376–402`: the check, the banner, the `Installer` observer;
  - `MainActivity.kt:90`: `Updates.undismiss` (`EXTRA_SHOW_UPDATE`);
  - `SettingsFragment.kt:81–84`, `:165`, `:279–286`: the updates switch and Check now.
    - In `fdroid`, hide both rows and keep "Installed version".
    - What's new (`WhatsNewActivity`) fetches release notes from GitHub, but only when opened. That's user-initiated,
      so it can stay. Hide it anyway if a reviewer asks: F-Droid shows the fastlane changelog itself.
- **Cleaner alternative:** put `update/Updates`, `update/Installer` and `update/UpdateActivity` in `src/github/`, with
  no-op stubs in `src/fdroid/`. The scanner and reviewers then see no download-and-install code at all. That costs
  more, but reviewers prefer it. Pick when building.
- **Tests:** `dns/UpdateCheck` stays shared, since it's pure logic and harmless. Add a unit test that the `fdroid`
  merged manifest has no `REQUEST_INSTALL_PACKAGES`, or a CI step that greps the merged manifest. disc-tracker has
  the same idea in its plan as a permission allowlist.
- **Bug-report forms** (Settings → Feedback) open GitHub in the browser. That's allowed: it's user-initiated and not
  a webview.

### W2 — Store text in the repo. About half a day.

Add `fastlane/metadata/android/en-US/` (copy disc-tracker's layout):
- `title.txt` and `short_description.txt` (80 characters at most);
- `full_description.txt` (4000 characters at most; a small HTML subset, not Markdown);
- `images/icon.png` (512 px), and `images/phoneScreenshots/`, taken on a clean test profile: no real sites, names or
  partner data;
- `changelogs/<versionCode>.txt` for each version code built from then on.
- Check it all with the skill's `scripts/check-fastlane.py`.

The full description must explain each sensitive permission and service in plain words, because testers compare them
with the Code Quality report:
- the **VPN** (DNS only; normal traffic never enters the app);
- **accessibility** (reads the address bar for section rules and screen checks; presses Back on blocked apps);
- **usage stats**, **screenshots** (on-device check; a blurred 48 px thumbnail to partners, D-037), **device admin**
  (uninstall notice, never blocks uninstall), **notifications**, the **battery-optimisation exemption** and
  **boot**.
- Also: the server is optional, free software and self-hosted (`server/`, AGPL-3.0), with no default server.

`tools/changelog.py` could also write the fastlane changelog from the same PR titles (D-031). Small, optional.

### W3 — The recipe. Small.

`metadata/app.focusfence.accountable.yml` (or the D1 ID) in a fork of `fdroiddata`, on a branch made from a freshly
fetched `upstream/master` (skill rule 2). Draft, to be checked against `templates/` and against recent plain-Gradle
recipes:

```yaml
Categories:
  - Security          # (check) current category list
License: AGPL-3.0-only  # (check) the README badge says AGPL-3.0 with no version rule; agree -only or -or-later with both copyright holders
AuthorName: Focus Fence contributors
SourceCode: https://github.com/flyboy-byte/focusfence
IssueTracker: https://github.com/flyboy-byte/focusfence/issues
Changelog: https://github.com/flyboy-byte/focusfence/blob/HEAD/CHANGELOG.md

AutoName: Focus Fence AC
RepoType: git
Repo: https://github.com/flyboy-byte/focusfence.git
Binaries: https://github.com/flyboy-byte/focusfence/releases/download/v%v/focus-fence-accountable-fdroid-v%v.apk

Builds:
  - versionName: X.Y.Z-accountable
    versionCode: NN
    commit: <full 40-char SHA of the tag>
    subdir: app
    gradle:
      - fdroid

AllowedAPKSigningKeys: <sha256 of the release cert, lower-case, no colons>

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+-accountable$
CurrentVersion: X.Y.Z-accountable
CurrentVersionCode: NN
```

- **Tags and names.** Tags are `vX.Y.Z-accountable` and `versionName` already carries `-accountable`, so `%v` in
  `Binaries` expands to the full name. Then the file name has `-accountable` twice in the tag part: check the expanded
  URL against a real release asset.
- **Monorepo.** `server/` is in the same repo; `subdir: app` builds only the app, but the scanner reads the whole
  tree. Committed binaries today: the two `.tflite` models and `gradle/wrapper/gradle-wrapper.jar` (the wrapper is
  handled by fdroidserver). If the scanner flags the models, add a targeted `scanignore:` for exactly
  `app/src/main/assets/classifier` with a reason. Never a broad one.
- Lint with `scripts/check-recipe.py`, then let fork CI's `fdroid rewritemeta` decide formatting (skill rule 4).

### W4 — Reproducible signing (D3 = a). Medium; a one-time setup, then one step per release.

- **The reference APK has to be F-Droid's build.** The `fdroid` flavour differs from the GitHub APK, so the GitHub
  APK can't be the reference. The reference is F-Droid's own unsigned `fdroid` build from fork CI, signed with our
  release key using `scripts/sign-reference.sh`. That script does `--v1-signing-enabled false --alignment-preserved
  true` and checks the cert against `AllowedAPKSigningKeys` first.
- **The key.** The release key (D-021) lives in CI secrets; @sidewinderzz controls the repo secrets. Signing a
  reference needs the keystore on the machine that runs the script, or a manual `workflow_dispatch` job in
  `release.yml` that takes an unsigned APK artifact and signs it. A CI job keeps the key off laptops; decide when
  building, with both maintainers. Never print or commit a password.
- **Release flow, once on F-Droid** (replaces today's, from "AGENTS.md → Releases"):
  1. Bump the version, add the fastlane changelog, merge, **don't tag yet**.
  2. Fork CI, Run 1: get the unsigned `fdroid` APK (`scripts/fetch-ci-apks.sh`).
  3. Sign it with the release key.
  4. `gh release create vX.Y.Z-accountable --target <sha>` with **both** APKs attached: the GitHub flavour, and the
     signed F-Droid reference. That creates the tag with both files already there (skill rule 6).
  5. Run 2 on the fork must pass the byte comparison.
  6. checkupdates does the rest. Check it days later with `scripts/fdroid-status.sh` and the build log at
     `monitor.f-droid.org` (skill rules 3 and 12).
- `release.yml` today builds and publishes the release on the tag push. It needs to build the `github` flavour, and
  either attach a pre-signed reference or let step 4 upload it by hand. Don't let a workflow create the release before
  the reference APK exists.
- **Expected determinism issues** (from the skill's catalogue; fix only the class the diff names, one per attempt):
  - `META-INF/version-control-info.textproto`: AGP 8.3+ VCS info; build from the clean tag, or
    `vcsInfo { include = false }`;
  - `assets/dexopt/baseline.prof`;
  - more than one JDK on the build machine.
  - The `.tflite` assets and the TFLite `.so` files are copied as-is, so they shouldn't differ.

### W5 — Repo docs. Small, done alongside W1–W4.

- `docs/fdroid.md`: the per-app F-Droid doc the skill asks for. It holds a dated status line, ID, fork and branch, cert
  fingerprint, this runbook as it works, every reviewer request, and an attempt log for reproducibility.
- Write the keystore path and alias in a gitignored note, never the password.
- Update `AGENTS.md` → Releases, `CLAUDE.md` → Self-update, and the README → Install. Add an F-Droid badge only after
  it's listed.

## Review risks (what a reviewer may raise, and the answer)

| Risk | Likelihood | Answer / plan |
|---|---|---|
| Self-update or download code present | certain, unless W1 | W1; the file-split option removes the code entirely |
| `Tracking` for update checks without permission | certain, unless W1 | gone with W1. Nothing else contacts a server by default: no default server; DNS goes to the network's own resolver, and to 1.1.1.1/8.8.8.8 over DoH only as a fallback |
| Bundled ML weights (GantMan MIT, NudeNet AGPL-3.0), trained on data that isn't published | medium | the licences are free, and both licence files ship next to the models; `tools/convert_nudenet_model.py` shows where the file came from. If pushed, we may have to accept `NonFreeAssets` (D5). *(check how F-Droid currently treats bundled models)* |
| Accessibility + VPN + screenshots + usage stats look like stalkerware | medium | it monitors the owner's own phone, by their choice; capture always shows its indicator (AGENTS.md rule); uninstall is never blocked; partners get a blurred 48 px thumbnail, never clear images. Say this up front in the description and the MR |
| `NSFW` anti-feature | low | the app doesn't show adult content; partners get blurred thumbnails. Describe it as accountability, not adult content |
| App ID / domain | depends on D1 | settle D1 before the MR |
| Size (40 MB) | low–medium | D4 splits; turning R8 on was deferred for TFLite reflection, so test that on a device if it's asked for |
| Two maintainers | low | `AuthorName` and contact must be someone who will answer review; Logan is the MR author |

## Exit conditions

1. The `fdroid` flavour builds in CI. Its merged manifest has no `REQUEST_INSTALL_PACKAGES`, and it never contacts
   GitHub. Check that by watching its traffic (e.g. PCAPdroid) on a phone 📱.
2. Fork pipeline green on every job, including `rewritemeta`, the scanner and the build; Run 2 passes the byte
   comparison.
3. The F-Droid-built APK installs and runs on a phone (skill rule 13). An existing GitHub install updates to it, or
   back, only if D1 and D3 allow.
4. MR merged, and the version listed on f-droid.org. Report each layer separately.

## Do not

- Start before Logan says so.
- Drop `Binaries`/`AllowedAPKSigningKeys` to get past a mismatch.
- Use a broad `scanignore`.
- Put screenshots with real data in fastlane.
- Change the app ID for existing users without D1.
- Upload a reference APK whose cert wasn't checked first.
