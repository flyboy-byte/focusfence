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

## Pull requests: explain yourself to the other maintainer

Two people (@flyboy-byte, @sidewinderzz) and their agents work on this repo, and either may merge the other's
work. A PR should let the other person understand **why**, not just what, without a conversation. So every PR
description has these sections (the template in `.github/pull_request_template.md` pre-fills them, and the
`PR contract` check fails a PR that leaves one empty):

- **What and why**: the change and the reason, in plain words.
- **Pros**: what gets better.
- **Cons and risks**: what gets worse or could break. Don't leave it at "none"; there is always a cost.
- **What the other maintainer might object to**: put yourself in their place. Name the likely objection (scope,
  privacy, battery, a decision in `DECISIONS.md`, their own work this touches) and say why it's still worth it,
  or what you'd change if they disagree. A sentence or two; this is courtesy, not a defence brief.
- **Tested**: 📱 on a phone / 🧪 CI only / not tested, and what exactly.
- **How to revert**: usually "revert the squash commit"; say if data, a server deploy or a release is involved.

Keep PRs to one feature or fix, and title them with a type and scope (`feat(dns): …`, `fix(server): …`,
`docs: …`, `refactor(ui): …`, `perf(sync): …`, `chore: …`): squash merges inherit the title, and the changelog is
built from it.

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
- Bump `versionCode` / `versionName` in `app/build.gradle.kts` before tagging.
- Some agent sandboxes can't push tags or edit releases. If yours can't, give the human the exact
  commands (they run them from Termux with `gh`).

## Sandbox notes (Claude Code cloud sessions)

- GitHub GraphQL is blocked: use `gh api repos/...` (REST) instead of `gh release list` etc.
- Tag pushes and release edits may be refused; branch pushes work.
- There's no Android SDK emulator; you can't run the app. Lean on unit tests, CI, and the
  human's log exports.
