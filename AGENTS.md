# AGENTS.md

Rules and context for any AI coding agent (Claude, Codex, Copilot, …) working in this repo.
Humans are welcome to read it too.

**Read first, in this order:**
1. [`STATE.md`](STATE.md) — what's built, what's verified, what's next.
2. [`CLAUDE.md`](CLAUDE.md) — architecture and invariants (despite the name, it applies to every agent).
3. The spec for whatever you're touching in [`docs/specs/`](docs/specs/).

## Who you're working for

The person directing the work (GitHub `flyboy-byte`) is not the repo owner (`sidewinderzz`), is
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

- Work on `claude/logging-and-accountability-plan` (or a branch off it). **Never push to `main`
  or the default branch, never force-push, never merge** without the owner asking.
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

## Commands

```sh
./gradlew testDebugUnitTest   # JVM unit tests (the only automated tests)
./gradlew assembleDebug       # debug APK
./gradlew lint
python3 -I tools/analyze_logs.py <export.zip>   # summarise a phone's log export
```

CI (`.github/workflows/android.yml`) runs tests + debug build on every push. **CI is the
authority** on whether the app compiles; check it after pushing (`gh run list` / `gh run watch`
with `-R sidewinderzz/focusfence`).

## How to add or change a feature

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
| Device owner provisioning | `dpm set-device-owner` fails while any account is on the phone. |
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
- Some agent sandboxes can't push tags or edit releases. If yours can't, give the human the exact
  commands (they run them from Termux with `gh`).

## Sandbox notes (Claude Code cloud sessions)

- GitHub GraphQL is blocked: use `gh api repos/...` (REST) instead of `gh release list` etc.
- Tag pushes and release edits may be refused; branch pushes work.
- There's no Android SDK emulator; you can't run the app. Lean on unit tests, CI, and the
  human's log exports.
