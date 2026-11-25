# AGENTS.md

Rules and context for any AI coding agent (Claude, Codex, Copilot, …) working in this repo.
Humans are welcome to read it too.

**Read first, in this order:**
1. [`STATE.md`](STATE.md) — what's built, what's verified, what's next.
2. [`CLAUDE.md`](CLAUDE.md) — architecture and invariants (despite the name, it applies to every agent).
3. The spec for whatever you're touching in [`docs/specs/`](docs/specs/).

## What changed lately (for agents; the full list is [`CHANGELOG.md`](CHANGELOG.md))

Read these before touching partners, the server, updates or block notices. Newest first.

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

- **Branches (D-022):** start every task on a short branch from fresh `origin/main` and open a PR to
  `main`. **Never push to `main`, never force-push a shared branch, never merge** unless the person
  directing you asks. Never tag a release; releases are a separate version-bump PR plus a tag on `main`.
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

## Pull requests: awareness, not rules

Two people (@flyboy-byte, @sidewinderzz) and their agents send PRs into one `main`, and either may merge the
other's work. Nothing here is enforced, by a check or by an agent: it's a habit that makes the other person's job
easier. When you write a PR, think about who will read it, and cover what they'd want to know. The template in
`.github/pull_request_template.md` offers these sections; use the ones that help, in a sentence or two each:

- **What and why**: the change and the reason, in plain words.
- **Pros** and **cons and risks**: what gets better, what gets worse or could break.
- **What the other maintainer might think**: their likely view (scope, privacy, battery, a decision in
  `DECISIONS.md`, their own work this touches), and why it's still worth it, or what you'd change if they disagree.
- **Tested**: 📱 on a phone / 🧪 CI only / not tested, and what exactly.
- **How to revert**: usually "revert the squash commit"; say if data, a server deploy or a release is involved.

**Agents:** write your own PRs this way, and when you notice the other maintainer's work nearby (an open PR on the
same files, a decision of theirs this touches), say so in your PR. Never block, reject, rewrite or gate someone
else's PR for missing a section; mention it at most, kindly.

Keep PRs to one feature or fix, with a typed title (`feat(dns): …`, `fix(server): …`, `docs: …`, `refactor(ui): …`,
`perf(sync): …`, `chore: …`). **The title is user-facing:** it becomes a line in the changelog and in the app's
"What's new" (D-031), so write it for the person using the app, in plain words ("block notices for adult and gambling
sites", not "wire BlockTriage into DnsVpnService"). Put the type in the prefix, not a bare `ui:`.

## Commands

```sh
./gradlew testDebugUnitTest   # JVM unit tests (the only automated tests)
./gradlew assembleDebug       # debug APK
./gradlew lint
python3 -I tools/analyze_logs.py <export.zip>   # summarise a phone's log export
```

CI (`.github/workflows/android.yml`) runs tests + debug build on every push. **CI is the
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
   records; new log fields go in `dns/LogEvents` **and** `docs/specs/event-log.md` together.
6. No timers or polling for background work: piggyback on existing broadcasts (see `Monitor`) or
   use WorkManager. Battery is a feature.
7. Update the relevant spec, and **`STATE.md`** if a status changed.

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
| Assuming a 🧪 feature works | See the Incidents table in `STATE.md`. |

## Releases

- Push a tag `vX.Y.Z-accountable` → `.github/workflows/release.yml` runs tests, builds a release
  APK, verifies the signature with `apksigner`, and publishes a GitHub Release — a full release
  marked Latest, unless the tag contains `-rc`, `-beta` or `-test` (then a pre-release).
- Signing: if repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
  exist, the stable key is used (updates install over each other). Otherwise a throwaway key is
  generated and the notes say so. The expected cert SHA-256 is in `STATE.md`.
- Release = one small PR titled `chore: release X.Y.Z` that bumps `versionCode` / `versionName` in
  `app/build.gradle.kts` **and** refreshes the changelog (`tools/changelog.py full > CHANGELOG.md`);
  after it merges, tag `vX.Y.Z-accountable` on `main`. Never bump the version in a feature PR.
- The changelog is generated, never hand-edited: one line per squash-merged PR, grouped by the type in
  its title, each naming the commit to `git revert`. `tools/changelog.py notes <last-tag>` prints just
  the new section, for the release description.
- The release body's `## What's new` section is generated by `release.yml` from the changelog, and the app's update
  banner and What's new screen read it (`UpdateCheck.userNotes`). Keep that heading.
- **No downgrades** (D-030): Android won't install an older version over a newer one. A bad release is fixed by a new
  patch release that reverts the change.
- The server deploys separately (private runbook, not in this repo). When a change touches both, deploy the server
  first if the app depends on it.
- Some agent sandboxes can't push tags or edit releases. If yours can't, give the human the exact
  commands (they run them from Termux with `gh`).

## Sandbox notes (Claude Code cloud sessions)

- GitHub GraphQL is blocked: use `gh api repos/...` (REST) instead of `gh release list` etc.
- Tag pushes and release edits may be refused; branch pushes work.
- There's no Android SDK emulator; you can't run the app. Lean on unit tests, CI, and the
  human's log exports.
