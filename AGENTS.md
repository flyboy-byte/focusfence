# AGENTS.md

Rules and context for any AI coding agent (Claude, Codex, Copilot, …) working in this repo.
Humans are welcome to read it too.

**Read first, in this order:**
1. [`STATE.md`](STATE.md) — what's built, what's verified, what's next.
2. [`CLAUDE.md`](CLAUDE.md) — architecture and invariants (despite the name, it applies to every agent).
3. The spec for whatever you're touching in [`docs/specs/`](docs/specs/).
4. [`docs/HANDOFF.md`](docs/HANDOFF.md) → Next for work in progress, and the plan for the area you touch in
   [`docs/plans/`](docs/plans/) (17 is screen text and screenshots, 15 repo health, 13/14 partners and groups).

## What changed lately (for agents; the full list is [`CHANGELOG.md`](CHANGELOG.md))

Read these before touching partners, the server, updates, block notices or screen checks. Newest first.

- **3.1.7 (2026-11-27), logs locked:** `crash` / `exit` / `net` change records, `fwd_err_*`, `dns/Faults`;
  the format is add-only (rules in `docs/specs/event-log.md`).
- **3.1.6 (2026-11-27):** screen-check tuning from a real export. `dns/SecretFields` (a revealed web password box isn't
  `isPassword`); erotica words in `RiskTerms`, quitting words cancel only adult words within 40 words; for 3 min after
  alert-level words, pictures face lower bars (`FlagPolicy.inContext`); body check from 0.30, `BodyCheck.FEMALE_BOOST`;
  Flag sensitivity (`dns/Sensitivity`, Strict 0.45 / Standard 0.50 / Relaxed 0.70, guarded `LOWER_SENSITIVITY`), and
  suggestive pictures at the bar now flag. Text alerts name the app only: alert text is server-readable.
- **3.1.5 (2026-11-27):** plan 17: `RiskTerms` word index (disguises, phrases), text alerts, screenshot on adult words,
  each picture checked on its own (`dns/ImageRegions`), hot mode (`TextCapture.markHot`), and a fix for a blind spot
  after leaving Focus Fence (`UrlFilterService.watchingAnything`).
- **3.1.4 (2026-11-27):** targetSdk/compileSdk 35, AGP 8.7.3; Android 15 edge-to-edge handled once in `ui/EdgeToEdge`.
  Newer AndroidX lines need compileSdk 36.
- **3.1.0–3.1.3 / server 2.1.0 (2026-11-26):** plan 14 groups (partner pairs both ways, `Partner.groupIds`), QR invites,
  one join box, slower address-bar poll during video, plan 15 fixes.
- **3.0.0 / server 2.0.0 (2026-11-26):** plan 13, D-042: one kind of partner, a person with devices, each with its own
  P-256 key; the phone seals its report key to each device (`/v1/seal`), the server relays only ciphertext.
- **2.26.0 / server 1.3.0 (2026-11-25):** partners choose how alerts reach them and their quiet hours, ask-a-partner
  unlocks, leaving is never silent.

- **2.25.0 / server 1.2.0 (2026-11-25):** plan 10 first-time journeys (5-step onboarding, adult blocked by default,
  self-host path, SMTP, guarded Leave this server, no-email invites, loosening alerts); protocol window D-041;
  Settings → Feedback & bug reports (three GitHub issue forms).
- **2.24.0 (2026-11-25):** Block lists rework (`CategoriesActivity`: three-way buttons, plain names, strength row,
  Always allow group, weekly `data/ListRefresh` instead of an "Update list" button per list). Updates happen in Home's
  banner (`update/Installer`; size, progress, retry); the notification opens Home.
- **2.23.0 (2026-11-25):** "suggestive" tier: NudeNet's covered-part classes on a risky picture are logged and shown
  in the partner report, report-only (no alert, no picture). `BodyCheck.classify`.
- **2.22.0 (2026-11-25): licence MIT → AGPL-3.0 (D-038)** so the app can bundle NudeNet. A partner flag (and an automatic
  send) now needs NudeNet to find an exposed body part; the GantMan model alone flagged faces on video calls. Review
  cards show "body check: …". Releases up to 2.21.0 stay MIT.
- **2.21.0 / server 7edb093 (2026-11-25, PR #89):** D-036 dead man's switch: server silence
  window 60 min, "checking in again" notice, and the server keeps what partners were told (`Device.alerts`) for app
  partners to poll at `/p/{token}/alerts` (mailbox alerts are skipped when that answers). D-037: flagged screenshots go
  to partners automatically, blurred, 10 min after the flag, with an owner notification and inline note
  (`dns/AutoShare`, `monitor/AutoShareNotice`, `ShareQueue.enqueue(auto = true)`). Delivery: the open report slice is
  re-sent at every sync and the server replaces it by `from`, so reports are ~15 min fresh. Report page: status card,
  Needs attention, one timeline (server alerts + changes), day bars, inline bars. **Deploy the server first**: an old
  server would store each re-sent slice as a new one and double the counts.

- **2.20.0 / server dcadeb4 (2026-11-25):** an app partner gets its own `/p/{token}` report link and the app opens that
  server page in a WebView: one report for every partner. The report page has a range switch, a summary and tabs with
  sortable tables (`server/src/main/resources/static/report.js`).
- **2.19.x / D-035 (2026-11-25):** one partner invite (`dns/InviteLink`); every phone enrolled (an invite is an
  enrollment credential); only enrolled phones open new mailboxes; the server records app partners; *Join with an
  invite* replaced typing a pairing key; Server → Save connects and reports back. Admin sign-in is an emailed code **or**
  an authenticator. Old pairing-key flows are gone on purpose (Logan is the only user).
- **2.18.x (2026-11-25):** update check every time the app comes to the foreground (`FocusFenceApp` counts started
  activities, because the VPN keeps the process alive), then every 20 min. Partners hear within seconds when uninstall
  protection is turned off (`SyncWorker.pushAlertsNow`).
- **2.16–2.18 (D-034):** rounded groups (`ui/Tiles`), colour only from the system theme, block notices are a
  notification or nothing (no full-screen page), Block history.
- **Server delivery (D-033):** Docker Compose, or the release tarball's `install.sh` (systemd, health check, rollback).
  When a change touches both ends, **deploy the server before releasing the app**.
- **Release builds** can fail in `lintVitalAnalyzeRelease` on Maven Central 429s: re-run the failed job on the same tag.

## Who you're working for

The person directing the work (GitHub `flyboy-byte`, the repo owner since 2026-11-24) is
short on time, and **cannot watch you work or review every diff**. That shapes everything below:

- **Verify, don't assert.** Before claiming something works, show the evidence: a test, a CI run,
  a log line, a source you checked. If you couldn't verify it, say so plainly.
- **Label claims by verification level** (📱 phone / 🧪 CI / 📝 prototype / ❌ not built — see
  `STATE.md`). Never describe phone behaviour you haven't seen confirmed.
- **Review your own diff before pushing.** Twice this project shipped a change whose comments
  claimed one thing while the code did another.
- **Report results, not process.** Short summaries; what changed, what's verified, what's needed
  from the human.

## Hard rules

- **Landing work (D-043):** ordinary changes go straight to `main`, risky ones by pull request (below). Never
  force-push `main` unless the maintainer asks for a history rewrite. Never tag a release unless the maintainer asks.
- **Never commit signing material**: `*.jks`, `*.keystore`, `keystore.properties`, base64 keys,
  passwords. `.gitignore` covers the common names; check anyway.
- **Never widen the tunnel routes.** DNS-only routing is the whole design (battery, privacy).
- **Never use VPN lockdown** (`setAlwaysOnVpnPackage(..., lockdown = true)` or telling the user to
  enable "Block connections without VPN"). With DNS-only routes it cuts off the internet.
- **Never block uninstall** (`setUninstallBlocked`). The stance is detect-and-notify: removal must
  stay possible but never silent.
- **Capture must stay visible.** Screen/text capture always shows the on-device indicator. No
  hidden monitoring.
- Treat log exports as private data: analyse them locally, never send them anywhere.
- **The repo is public, and this is an app about porn.** Code, comments, docs, tests, commits, PR bodies and release
  notes describe what the app does and why, in general terms. Never: what the owner (or anyone) viewed, when, or in
  which app; their words about misses or temptations; which settings reflect their personal preferences; alert or flag
  counts from a real phone; test strings modelled on a real page (make them up). Don't quote a real case even as a
  bad example.

## How changes land (D-043)

There is one active maintainer (@flyboy-byte), who can't review every diff, so the checks are the review. Phones and
self-hosted servers only ever get tagged releases, never `main`.

| Change | How it lands |
|---|---|
| Docs, bug fixes, detection tuning, UI, logging, tests | Commit to `main` and push |
| Server code, `dns/Protocol`, partner encryption, stored partner data | Pull request, merged when CI passes |
| Updater, signing, `.github/workflows/` | Pull request, merged when CI passes |
| Anything big or half-sure, or that the maintainer wants to see first | Pull request |
| Anyone else's work | Pull request ([`CONTRIBUTING.md`](CONTRIBUTING.md)) |

Direct pushes are for the maintainer and agents working on their request.

**Before every push to `main`:**

1. `git pull --rebase`.
2. `tools/check.sh` passes (public-docs check, app tests, build, lint, server tests).
3. Read your own diff, and read the added lines as a stranger would (the public-repo rule above).
4. One change per commit, so it can be reverted alone.
5. Docs the change makes untrue are fixed in the same commit.

CI also runs on `main`. Check it before ending a session; if it's red, fix or revert before anything else.

**Titles** (commits and PRs) are typed: `feat(dns): …`, `fix(server): …`, `docs: …`, `refactor(ui): …`,
`perf(sync): …`, `chore: …`. **The title is user-facing:** it becomes a line in the changelog and in the app's
"What's new" (D-031), so write it for the person using the app, in plain words ("block notices for adult and gambling
sites", not "wire BlockTriage into DnsVpnService"). Put the type in the prefix, not a bare `ui:`.

**When docs get updated**, so they don't become work of their own:

| When | Update |
|---|---|
| With a change | The spec or plan it changes; README and privacy page only if what a user sees changed; the `STATE.md` row if a status moved |
| At a release | `CHANGELOG.md` (generated), `STATE.md` latest release, `docs/HANDOFF.md`, all in the release commit |
| End of a session | `docs/HANDOFF.md`, only if the next session needs it |

## Commands

```sh
tools/check.sh                # everything below plus server tests: run before pushing to main
./gradlew testDebugUnitTest   # JVM unit tests (the only automated tests)
./gradlew assembleDebug       # debug APK
./gradlew lint
python3 -I tools/analyze_logs.py <export.zip>   # summarise a phone's log export
```

CI (`.github/workflows/android.yml`) runs tests + debug build on pushes to `main` and on pull requests. **CI is the
authority** on whether the app compiles; check it after pushing (`gh run list` / `gh run watch`
with `-R flyboy-byte/focusfence`).

## How to add or change a feature

UI changes follow D-032: colour only for state (through `ui/StatusColors`), plain lists over nested cards, short
copy, each screen ordered by rank. Look at Home and Settings before inventing a new pattern.

1. Put decision logic in `app/src/main/java/app/focusfence/dns/` (pure Kotlin, no Android
   imports) with a JUnit test. Services/UI supply clocks, context and I/O.
2. Settings the services react to go in the `focusfence` prefs file and must be handled in the
   services' prefs listeners (the only propagation mechanism).
3. User-visible text goes in `res/values/strings.xml`.
4. Anything that loosens protection goes through `UiUtil.guard(context, GuardPolicy.Action.X)`.
5. A new protection component needs an `integrityState` key in `Monitor` and `svc` lifecycle
   records. **The log format is locked** (2026-11-27): add fields and counters, never rename or remove them; a new
   feature counts its volume and misses and logs each way it fails. New log fields go in `dns/LogEvents`,
   `LogSchemaTest` and `docs/specs/event-log.md` together.
6. No timers or polling for background work: piggyback on existing broadcasts (see `Monitor`) or
   use WorkManager. Battery is a feature.
7. Update the relevant spec, and **`STATE.md`** if a status changed.
8. Removing a feature: the plan's clean-cut section lists every public doc to update, and the same change adds the removed
   words to `tools/check_docs.py` (CI fails on any public doc that still names them; a line that describes the past
   on purpose ends with `<!-- docs: past -->`).

## Known traps (each one already bit this project)

| Trap | Why |
|---|---|
| Blocked answers as NXDOMAIN | No SOA means uncacheable; clients re-ask constantly. Use `Dns.buildBlockedResponse`. |
| Non-blocking tun fd | Reader becomes a busy spin. Keep `setBlocking(true)`. |
| Relaying only to public resolvers | Failed on the test network. Network's own resolver first, DoH fallback. |
| `HttpURLConnection.disconnect()` on DoH | Closes the socket on Android: a TLS handshake per lookup. |
| Sockets from the VPN app without binding | Bind to the underlying `Network` (or `protect()`) so they can't loop into the tunnel. |
| Strict Private DNS + our VPN | Lookups skip the tunnel entirely. `VpnHealth` reports `bypassed`. |
| Shutting down an executor in `stopVpn()` | The service restarts; a dead executor throws. |
| `setMultiChoiceItems` with a `null` listener | The `checked` array is only updated when there is a listener: ticks are silently dropped. |
| Refusing a VPN restart in strict mode | A restart applies settings and comes straight back; only a real stop is refused. |
| An integrity check during a VPN restart | Records "stopped" then "running" and alerts partners. `dns/RestartGrace`. |
| Trusting `isPassword` | A web form's password box loses it once "show password" is tapped. `dns/SecretFields`. |
| Waiting for a window event to know the front app | Android sometimes sends none when the app underneath comes back. `UrlFilterService.watchingAnything` polls. |
| One guard word silencing a page | A long page mentions anything once. Guards in `RiskTerms` work by distance (`QUIT_REACH`). |
| Assuming a 🧪 feature works | See the Incidents table in `STATE.md`. |

## Releases

- Push a tag `vX.Y.Z-accountable` → `.github/workflows/release.yml` runs tests, builds a release
  APK, verifies the signature with `apksigner`, and publishes a GitHub Release — a full release
  marked Latest, unless the tag contains `-rc`, `-beta` or `-test` (then a pre-release).
- Signing: if repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
  exist, the stable key is used (updates install over each other). Otherwise a throwaway key is
  generated and the notes say so. The expected cert SHA-256 is in `STATE.md`.
- A release, only when the maintainer asks, and after they've tried the previous build:
  1. `main` is pushed and its CI is green.
  2. One commit, `chore: release X.Y.Z`: bump `versionCode` / `versionName` in `app/build.gradle.kts`, run
     `tools/changelog.py full vX.Y.Z-accountable > CHANGELOG.md`, update `STATE.md` and `docs/HANDOFF.md`. Push it.
  3. When CI is green on that commit, tag `vX.Y.Z-accountable` on it and push the tag.
- Never bump the version outside a release commit. Batch fixes into one release.
- The changelog is generated, never hand-edited: one line per commit on `main`, grouped by the type in its title,
  each naming the commit to `git revert`. `tools/changelog.py notes <last-tag>` prints just the new section.
- The release body's `## What's new` section is generated by `release.yml` from the changelog, and the app's update
  banner and What's new screen read it (`UpdateCheck.userNotes`). Keep that heading.
- **No downgrades** (D-030): Android won't install an older version over a newer one. A bad release is fixed by a new
  patch release that reverts the change.
- The server deploys separately (private runbook, not in this repo). When a change touches both, deploy the server
  first if the app depends on it.
- **Protocol window (D-041):** changing the shape of anything the app sends or reads (path, JSON field, signature format)
  means raising `dns/Protocol.LEVEL` and adding `server/src/test/.../compat/CompatLevel<N>Test` for the new level. Never
  edit an old level's suite to match a new server; delete it only once it falls out of the window. `release.yml` runs
  `tools/check_protocol.py` against the newest `server-v*` tag.
- **Keep the server release current** (Logan, 2026-11-25): self-hosters install the newest `server-vX.Y.Z` tag, not
  `main`. When server code changes in a way the current app needs, tag a new `server-v` release (`server-release.yml`
  builds the tarball and the Docker image) and update the version in `server/README.md`. Minor bump for features,
  patch for fixes.
- Some agent sandboxes can't push tags or edit releases. If yours can't, give the human the exact
  commands (they run them from Termux with `gh`).

## Sandbox notes (Claude Code cloud sessions)

- GitHub GraphQL is blocked: use `gh api repos/...` (REST) instead of `gh release list` etc.
- Tag pushes and release edits may be refused; branch pushes work.
- There's no Android SDK emulator; you can't run the app. Lean on unit tests, CI, and the
  human's log exports.
