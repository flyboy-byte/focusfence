# Handoff: how to pick this project up cold

_Rewritten 2026-11-23 (late), after the accountability work landed on `main`. Read order: this file → [`STATE.md`](../STATE.md)
(where things stand) → [`docs/roadmap.md`](roadmap.md) (what's next, in order) → [`AGENTS.md`](../AGENTS.md) (how to work
here). If this and `STATE.md` disagree, `STATE.md` wins._

## Who wants what
- **@flyboy-byte (Logan):** a self-accountability app (benchmark: Ever Accountable) on his Pixel 9, aimed at groups of up to
  4 phones; every layer optional ("recommended" means guided, never forced); FOSS spirit, light. Leads collaboration and
  the accountability direction. Short on time to test: verify harder than feels necessary and label 🧪 vs 📱.
- **@sidewinderzz (Shelby):** repo owner and admin; merged the accountability work into `main` (#12) and added the blocked
  screen/notification (#13, #14). His agents work from Claude project threads and open PRs too.
- **How we work together (D-022):** `main` is the only long-lived branch; short task branches, PRs, squash merges; releases
  are tags on `main`. PRs carry a little awareness for the other person (`AGENTS.md`, "Pull requests: awareness, not
  rules"); **nothing is enforced** and agents never gate anyone's PR.

## Working loop
1. Branch from fresh `origin/main`; build with `ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk`
   (`./gradlew testDebugUnitTest assembleDebug lint`, `./gradlew -p server test`). The system JDK 27 breaks Gradle 8.9; no
   `--offline`.
2. Open a PR to `main`. Agents don't push to `main`, merge or tag unless the person directing them asks.
3. **Releases:** a `chore: release X.Y.Z` PR (bump `versionCode`/`versionName`, `tools/changelog.py full > CHANGELOG.md`),
   merge, tag `vX.Y.Z-accountable` on `main`; `release.yml` signs with the one key (D-021, cert `2ca769a5…6098671`). Logan
   authorised Claude to tag, **but only after he has tried the previous build or asks**: no rapid-fire releases.
4. Exports: he uses **Settings → Logs & diagnostics → Save to Downloads** and drops files in `~/.claude/uploads/`; analyse
   locally only (private). Split numbers by version using the `app updated` records.
5. **Server deploys** (`ff.flyboybyte.com`, flyboybyte VPS): `./gradlew -p server installDist`, rsync to
   `flyboybyte:ffbackend-dist/`, then as root under a **Logan-opened `vps-sudo` window** (~20 min): copy `/opt/ffbackend`
   to `/opt/ffbackend.prev`, rsync in, `chown -R ubuntu:ffbackend`, restart, check `/healthz`, **restore automatically if
   unhealthy**, log in `~/security/hardening-log.md`. Never touch sshd, authorized_keys, ufw or sudoers; nginx only with his
   explicit OK.
6. **Secrets** live only in `/etc/ffbackend/env` (root:ffbackend 640): Resend sending-only key, bootstrap enrollment code,
   `FF_ADMIN_EMAILS` (Logan only). Never print them. Sender: `Focus Fence <focusfence@reports.flyboybyte.com>` (only the
   `reports.` subdomain is verified in Resend). The signing keystore is held by Logan (D-021); never in the repo.

## Decisions not to re-ask (details in `DECISIONS.md`)
- **Privacy:** the server operator can't read anyone's activity (D-023): email/web partner reports are sealed on the phone
  and opened in the partner's browser; groups and app pairing were already end-to-end. Every server user is independent
  until they choose to link.
- **Screenshots:** stay on the phone today; partners will **never** get a clear image of explicit content (level D ruled
  out). B (owner sends) vs C (blurred thumbnail) is still Logan's call (roadmap Phase 2).
- **Block feedback (D-024):** notification by default, optional full screen, triaged so trackers stay silent; no browser
  block page (P-006, would need a CA). Logan wants to revisit the triage details before it's built.
- Group visibility: everyone sees every filtered member by default. Adult/gambling bundles default to Report. Strict unlock:
  wait 10 min, window 15; a paired partner's code skips the wait, the wait always remains. Capture keeps its visible
  indicator. Never block uninstall, never widen the VPN routes, never commit signing material.

## Facts learned the hard way
| Finding | Where |
|---|---|
| Resend rejects an unverified sender domain with a 403 and the server used to swallow it | `LiveNotifier` logs status + message |
| The join redirect handed the joiner the confirm token, so double opt-in was bypassable | separate single-use email-only token |
| A malformed message in a shared mailbox could wedge everyone's sync | `GroupProtocol.openB64` never throws |
| `TypedArray.use{}` compiles but crashes before Android 12 | recycle by hand |
| Behind a proxy in a container every client shared one rate-limit bucket | `FF_TRUSTED_PROXIES` |
| Mailboxes keep 300 messages / 30 days, so late group joiners miss early history | join early, then "Close to new members" |
| An app update flips `vpn_metered` for a second, which mailed partners "filter off" | not an alert any more (2.9.1) |
| Nothing restarted the VPN after an update or reboot unless Always-on was set | `vpn/AutoStartReceiver` (2.9.2) |
| A morning of 3–7 s DNS lookups came from waiting out dead carrier UDP DNS | `dns/Hedged` races resolvers (2.10.0) |
| 7 of 8 reviewed AI flags were false (rocket-engine videos, a 1958 film, photo apps) | why screenshots stay on the phone |
| ~90% of ~1,800 blocks a day are trackers/telemetry; 0 adult or user-rule hits in 5 days | why D-024 triages |
| A `google.com` rule killed push notifications for every app | #18, fixed in #22 |

## Open questions only a person can answer
Screenshot level B or C (Logan); triage details (Logan, then Shelby on #13); keystore backup confirmed (Logan); repo
settings: default branch `main`, squash-only, auto-delete branches, close #11 (Shelby); whether the repo goes public (then
scrub `docs/plans/04-backend-deploy.md` and owner references); SMS provider and budget; running `server-release.yml`
(publishes a GHCR image under the upstream repo).
