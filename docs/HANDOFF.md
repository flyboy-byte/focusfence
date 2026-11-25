# Handoff: how to pick this project up cold

_Rewritten 2026-11-23 (late); updated 2026-11-25 (late) after 2.20.0 and the D-035 server deploy. Read order: this file → [`STATE.md`](../STATE.md)
(where things stand) → [`docs/roadmap.md`](roadmap.md) (what's next, in order) → [`AGENTS.md`](../AGENTS.md) (how to work
here). If this and `STATE.md` disagree, `STATE.md` wins._

## Who wants what
- **@flyboy-byte (Logan):** a self-accountability app (benchmark: Ever Accountable) on his Pixel 9, aimed at groups of up to
  4 phones; every layer optional ("recommended" means guided, never forced); FOSS spirit, light. Leads collaboration and
  the accountability direction. Short on time to test: verify harder than feels necessary and label 🧪 vs 📱.
- **@sidewinderzz:** wrote the original app; merged the accountability work into `main` (#12) and added the blocked
  screen/notification (#13, #14). Transferred the repo to @flyboy-byte on 2026-11-24 (now `flyboy-byte/focusfence`). His agents work from Claude project threads and open PRs too.
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
4. Exports: he uses **Settings → Logs & diagnostics → Save to Downloads** and shares them with the session; analyse
   locally only (private). Split numbers by version using the `app updated` records.
5. **Server releases and deploys (D-033):** tag `server-vX.Y.Z` on `main`; `server-release.yml` publishes the image and
   a tarball, never as GitHub's Latest. Deploy = unpack that tarball on the box and run `install.sh` (keeps the old
   copy, checks `/healthz`, rolls back by itself). The live box's specifics (host, root window,
   paths, what never to touch) are in the git-ignored `docs/ops.local.md` on Logan's machine; ask him if it's missing.
6. **Secrets** live only in the server's env file and the repo's Actions secrets. Never print them, never commit them.
   The signing keystore is held by Logan (D-021); never in the repo.

## Decisions not to re-ask (details in `DECISIONS.md`)
- **Privacy:** the server operator can't read anyone's activity (D-023): email/web partner reports are sealed on the phone
  and opened in the partner's browser; groups and app pairing were already end-to-end. Every server user is independent
  until they choose to link.
- **Screenshots:** partners **never** get a clear image (level D ruled out). Level B is built: the owner may send a
  48 px, once-blurred thumbnail (D-025 as amended). Automatic sending (C) waits on the AI's false-flag rate.
- **Accounts (D-035, 2.19–2.20):** one partner invite. In a browser it's an email partner; pasted into the app
  (*Join with an invite*) it enrolls that phone and pairs it. Every phone is enrolled, and only enrolled phones open
  mailboxes. The server knows who watches whom (Logan's choice) and gives an app partner its own `/p/{token}` link: the
  same report page, data and screenshots as email. Admin sign-in is an emailed code **or** an authenticator, not both.
  Logan is the only user: breaking old pairings is fine.
- **Block feedback (D-024, D-034):** a notification or nothing (the full-screen page is gone); Block history page; trackers silent; notices for
  own rules and adult/gambling/scam front doors; partners told after 3 confirmed tries in 10 min, by category. No
  browser block page (P-006, would need a CA).
- **Updates (D-029 as amended):** the app checks GitHub every time it comes to the foreground, then every 20 minutes, and offers updates (notify + tap, Home banner, What's
  new). No downgrades: a bad release is fixed by a patch release. Privacy pages live on GitHub Pages; the server no
  longer serves them.
- **UI (D-032):** plain in a good way, colour only for state and taken from the system theme (no fixed green),
  screens ordered by rank.
  Logan dislikes anything that reads or looks "obviously AI".
- **Server delivery (D-033):** Docker Compose for others, tarball + systemd for hosts without Docker. The server
  never becomes a DNS resolver for phones; the separate dns-filter stack was retired 2026-11-25 (repo archived).
- **Device owner is gone (D-028);** uninstall protection (device admin) stays.
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
| A strict-mode unlock request left no log record and no partner alert | #27: every strict change is a `svc strict` record and an alert |
| Squash-merging the bottom of a stacked PR set deletes its branch and GitHub **closes** the PR above (can't reopen after a force-push) | retarget the next PR to `main` *before* merging; #31 was replaced by #35 |
| No emulator on this machine, and a debug build can't install over the release app (different key) | UI changes are checked on Logan's phone from a release |
| GitHub sometimes 500s on push or loses a Pages artifact | retry the push; re-run the Pages workflow |
| `lintVitalAnalyzeRelease` failed 2.18.0 on Maven Central 429s, not code | `gh run rerun <id> --failed` on the same tag |
| The VPN keeps the process alive, so "app launch" is neither process start nor `MainActivity.onCreate` | started-activity count in `FocusFenceApp` (2.18.1, ✅ on the phone) |
| `dialog_two_fields` caps field 1 at 40 characters, which cut every pasted invite (2.19.0) | Join sets its own filters (2.19.1) |
| A `WebView` inside a wrap_content scroll parent fails release lint | it fills the screen beside the scroll view (2.20) |
| The ubuntu sudo window (`vps-sudo`) needs Logan's password; check `sudo -n true` before asking | deploys this session used an open window |

## Next (Logan, 2026-11-25: "the filter is pretty good; needs lots of work on accountability mechanics and networking")
"Networking" here means **accountability networking**: how phones, partners and the server reach each other.
0. **2026-11-25, branch `silence-alerts` (PR #89), built and tested, not deployed or released:** items 1 and 2 below
   (open slice re-sent each sync; app partners read server alerts; 60 min silence; back-online notice), the report page
   rework and automatic blurred screenshots (D-037). To ship: merge, deploy the server (set `FF_STALE_MINUTES=60` in
   `/etc/ffbackend/env`, it says 240), then release the app. Still to measure: an unplugged night; sync CPU after the
   15 min report uploads.
1. ~~**Report freshness**~~ — built on the branch: the open slice is re-sent every sync (~15 min).
2. **Alerts to app partners** poll every 15 min; silence is flagged after 14 h. Consider server push and a shorter
   silence window. Alerts and unlock codes still use the pairing mailbox; the report no longer does. Moving them onto
   the server route (the server already knows the pair) would leave one path.
3. **Internet DNS** is solid in daily use (Logan). The 2026-11-24 export still showed 8.9% forward failures, 673
   failovers, a 9.9 s worst p95 window and `vpn_metered` flapping. Gaming and latency-sensitive apps are untested. Ask for
   a normal day's export on 2.20.
4. Still unseen on the phone: the 2.16–2.18 screens (rounded Settings/Blocking, Block history, Notify me, uninstall
   copy) and groups on two phones.

## Open questions only a person can answer
## Open questions only a person can answer
How 2.13 to 2.15 look and behave on the phone (nothing seen yet; Logan's screenshots drive the next UI pass); a day's log
export on 2.15 for the battery pass; keystore backup confirmed (Logan);
~~repo settings and going public~~ done 2026-11-24 (D-027: transferred to @flyboy-byte, public as is); SMS provider and budget; running `server-release.yml`
(publishes a GHCR image under `flyboy-byte`).
