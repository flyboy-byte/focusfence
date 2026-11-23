# Handoff: how this project got here and how to keep working on it

_Rewritten 2026-11-23 (end of the long session on the laptop with VPS access). `STATE.md` says **where things stand**;
`AGENTS.md` says **the rules**; this adds the intent, the working loop, hard-won facts and what is half-done. If this and
`STATE.md` disagree, `STATE.md` wins._

## What the owner wants (Logan, GitHub `flyboy-byte`)
- A **self-accountability app** (benchmark: Ever Accountable) he uses on his Pixel 9, now **aimed at groups of up to 4 phones**,
  each filtered or partner-only, **everyone sees every filtered member by default** (narrowable). All of it **optional**: with
  every layer off it is still the light on-device blocker; server and screenshots are separate switches; "recommended" means
  guided, never forced. FOSS spirit, light.
- Not for publishing yet ("reality isn't that big"), but he may release it. He will **talk to @sidewinderzz himself** about landing
  on upstream `main`: **do no merge work, no PR, nothing on `shareddev` until he says so.** His view: "we're the better branch."
- **Keep going.** He has said "do whatever you want" and "I'm confused why you're stopping": work through reversible steps, note the
  default you picked in the docs. Stop for money, deleting things, overwriting his edits, or publishing something new.
- Limited time to test: verify harder than feels necessary and **label what is untested** (🧪 CI only vs 📱 phone).

## State in one screen (2026-11-23)
| Thing | State |
|---|---|
| App | **2.9.0-accountable** (code 28) tagged and pushed; the release workflow was still building when this was written. 2.8.0 is on his Pixel. |
| Server | Live at **https://ff.flyboybyte.com** on the flyboybyte VPS: Ktor 2.3.13, 33 tests, audited three times by the `extraction` session, unit exposure 1.5. One phone enrolled, uploaded, got a real "Filter turned off" email. |
| Partner loop on a phone (📱) | enroll, upload, invite, join, email confirm, filter-off alert. **Not yet seen:** weekly report, silence alert, other alerts, SMS, app-to-app pairing, any group feature. |
| Groups | Rules (`dns/Group`), protocol (`dns/GroupProtocol`, simulated 4-phone tests), storage/sync/screen all built; **never run on a phone or the server**. He is testing with a second phone tonight. |
| Docker/Compose | Image built and tested locally; compose file only parsed (no `docker compose` on the laptop); `server-release.yml` **never run** (a `server-vX.Y.Z` tag would publish a GHCR package and a release on the upstream repo: his call). |
| Branch | `claude/logging-and-accountability-plan`, never merged. 55+ commits ahead of the default branch; upstream `main` and `shareddev` have diverged (25 conflicting files, see `docs/plans/05-group-and-release.md`). |

## Working loop
1. Work on `claude/logging-and-accountability-plan`; run `./gradlew testDebugUnitTest assembleDebug` and `./gradlew -p server test`
   with `ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk` (the system JDK 27 breaks Gradle 8.9; no `--offline`).
2. Push; CI (`android.yml`) is the authority. Tags `vX.Y.Z-accountable` build a signed release (`release.yml`, secrets live in the
   repo). **He has authorised me to tag and push tags myself** (2026-11-23). Bump `versionCode`/`versionName` first.
3. He exports **Settings → Logs & diagnostics → Save to Downloads** and drops the files in `~/.claude/uploads/`; analyse locally
   only (private). Split numbers by version with the `app updated` svc records, not the whole-export summary.
4. Server deploys: build `./gradlew -p server installDist`, `rsync` to `flyboybyte:ffbackend-dist/`, then as root under a
   **Logan-opened `vps-sudo` window** (about 20 min): copy `/opt/ffbackend` to `/opt/ffbackend.prev`, rsync the new build into
   `/opt/ffbackend`, `chown -R ubuntu:ffbackend`, restart, health-check `/healthz`, **restore automatically if unhealthy**. Log it in
   `~/security/hardening-log.md` on the box. Never touch sshd, authorized_keys, ufw or sudoers; nginx only with his explicit OK.
5. Secrets: Resend (sending-only key) and the enrollment code are in `/etc/ffbackend/env` (root:ffbackend 640). Never print them;
   read the code for a new group member with `ssh flyboybyte "sudo grep ^FF_ENROLL_TOKEN /etc/ffbackend/env"`. The full-access
   Resend key was revoked by him. Sender is `Focus Fence <focusfence@reports.flyboybyte.com>` (only the `reports.` subdomain is
   verified in Resend).

## Facts learned the hard way (new this session)
| Finding | Where |
|---|---|
| Resend rejects an unverified sender domain with a 403 and the server used to swallow it silently | `LiveNotifier` now logs status + message |
| The join redirect handed the joiner the same token the confirm email used, so the double opt-in was bypassable | fixed: separate single-use email-only token |
| A malformed message in a shared mailbox could wedge everyone's sync (`B64.decode` threw before the cursor moved) | `GroupProtocol.openB64` never throws |
| `TypedArray.use{}` compiles but crashes before Android 12 | recycle by hand |
| Behind a proxy in a container the peer is not loopback, so every client shared one rate-limit bucket | `FF_TRUSTED_PROXIES` |
| The server keeps only 300 messages / 30 days per mailbox, so late joiners to a group miss early history | documented; join early then "Close to new members" |
| The 2026-11-22 "12.8% forward failure" in an export was a 16-hour no-network spell (travel), not a relay bug: 0.57% outside it | `STATE.md` incidents |
| AI flags so far: 4 of 5 were a photo app (Ente), one confirmed false; `FlagPolicy` holds those back, keeps the Brave 0.95 run | `docs/research/ai-detection.md` |

## Decisions already made (do not re-ask)
- Group visibility: everyone sees every filtered member by default. Privacy tier: server-readable records, screenshots E2E to the
  partner key ("less privacy now, harden later if we open it up").
- Adult/gambling bundles default to Report. Strict unlock: wait 10 min, window 15. Capture keeps its visible indicator. Never block
  uninstall, never widen the VPN routes. Never commit signing material.
- Landing on upstream is Logan's conversation, not ours. `docs/plans/05-group-and-release.md` section 7 has the talking points and a
  draft D-017.

## Half-done / next, in rough order
1. **Tonight: second phone.** Install 2.9.0 on both, create a group on A, join on B, compare fingerprints, approve both ways, check
   each sees the other's report and that an alert reaches the other. Expect bugs; get both exports.
2. Read the first export from 2.8.0/2.9.0: did the Always-on reminder fire, `flag_*` counters, time-to-email for a filter-off alert
   (he opened the app before the email arrived: unmeasured), Save to Downloads, a real light-mode run of `tools/check_light_mode.py`.
3. **Server admin and landing page**: `docs/plans/06-server-admin-and-landing.md`. Phase 1 (per-person enrollment codes) first.
4. Not done: device attestation at enroll (enroll code + caps are the stopgap), off-box backups, an outbound firewall for the
   service, fail2ban nginx jails (host-wide, they read the journal but nginx logs to files), `docker compose` end to end, first
   run of `server-release.yml`, report exclusions, screenshot upload to partners, Settings grouped into cards, "Recent flags".
5. Cleanups on the box once soaked: `/opt/ffbackend.prev` (kept deliberately as one-version-back rollback).

## Open questions only he can answer
Landing path on upstream (his talk with @sidewinderzz), D-017, signing custody (O-002), licence file (upstream has MIT), whether the
repo goes public (then scrub `docs/plans/04-backend-deploy.md`, this file and the owner references in `STATE.md`), SMS provider and
budget, admin/landing page decisions (section "Open questions" in plan 06).
