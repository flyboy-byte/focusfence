# Handoff: how this project got here and how to keep working on it

_Written 2026-11-22 from the long working session with the owner (Logan, GitHub `flyboy-byte`).
`STATE.md` says **where things stand**; `AGENTS.md` says **the rules**. This file adds what neither
holds: the intent behind decisions, how the owner works, and a few traps already hit. If this and
`STATE.md` disagree, `STATE.md` wins, since it is updated with each change._

## What the owner wants, in his terms

- **A self-accountability app, benchmarked against Ever Accountable**, that he actually uses on his own
  Pixel 9 now, and that a partner can be plugged into later. Not for publishing: "reality isn't that
  big", but secure and accountable on modern Android in general.
- **Usable without any backend.** Partners are an add-on. He can't deploy the server yet (it goes on
  his VPS, from a computer he doesn't have to hand), so nothing partner-side is testable.
- **Partner options as equals:** web + email (+ optional SMS) as the default, and app-to-app pairing
  by shared key. Shared-key pairing is preferred over a relay that holds secrets.
- **Device admin** (Ever Accountable style) as the default protection; device owner optional.
- **Detection that finds things while keeping battery cost low.** He reads the log exports himself.
- **Limited time to test.** Verify claims harder than feels necessary and label what is untested.
  He said so directly: "I don't have the time to … find bugs."
- **UI appeal matters but he can't say what to change.** Keep refining, with the design notes in
  `docs/research/ui-design.md`.

## How the work loop runs

1. Make a change on `claude/logging-and-accountability-plan` (never the default branch, never merge or
   force-push; the repo belongs to his cousin, `sidewinderzz`).
2. Run the JVM tests, push, wait for CI. CI sometimes fails on a Maven Central fetch; re-run it, don't
   "fix" code.
3. The sandbox **can't push tags or edit releases**, so the owner tags from Termux:
   ```sh
   cd ~/focusfence && git fetch origin
   git tag vX.Y.Z-accountable origin/claude/logging-and-accountability-plan
   git push origin vX.Y.Z-accountable
   # ~5–8 min later:
   gh release download vX.Y.Z-accountable -R sidewinderzz/focusfence -p '*.apk' -D ~/ffapk --clobber
   termux-open ~/ffapk/focus-fence-accountable-vX.Y.Z-accountable.apk
   ```
   Always give him exactly this, adjusted for the version. A corrupted download has happened once
   (hash mismatch): re-download.
4. He uses it for a day or so, then sends **Settings → Logs & diagnostics → Export** (a zip). Analyse it
   **locally only**; the logs are private. Report before/after numbers per version, since one export can
   span several versions.

## Facts that were learned the hard way

| Finding | Where it bit |
|---|---|
| Public-resolver-only DNS failed on his network; relaying to the **network's own DNS first**, DoH as fallback, fixed it | `Upstreams.order` |
| VPN **lockdown** ("Block connections without VPN") breaks everything with DNS-only routes. Never enable | `VpnHealth` LOCKDOWN |
| An Android VPN is **metered by default**; downloads dropped to ~14 kbps. `setMetered(false)` fixed it (confirmed by him) | 2.6.1 |
| Accessibility **content-changed events** were ~60–75% of screen-on CPU. Event diet + a foreground tick fixed it (87 → 38 s/h) | 2.5.0 |
| Screenshots never happened: missing `canTakeScreenshot`, swallowed exceptions | 2.3.2 |
| Games with anti-cheat refuse to run behind a VPN → exclusions, guarded by what the app can reach (`ExclusionPolicy`) | 2.6.3 |
| The strict-unlock **window was too short and the wait ended silently** → 15 min + a notification (untested on phone) | 2.7.2 |
| The only real AI flag so far was a **false positive** (shirtless selfie → `porn` 0.82). Don't tune on one point | `docs/research/ai-detection.md` |

## Verified on the phone vs only in CI

- **On his Pixel 9:** DNS filtering and relay, VPN-no-longer-throttling, screenshots + AI scoring
  (~91 ms), battery numbers in `STATE.md`, review screen *existence* (he asked for it and reported
  nothing broken, but didn't confirm each control).
- **Only tests/CI, never on a phone:** the whole partner loop (server, email, SMS, pairing), device admin
  as default, the unlock notification, the battery-exemption prompt, the Stats chart, owner review
  details. Treat these as unproven.

## Decisions already made (don't re-ask)

- Adult and gambling bundles default to **Report**, not Block. Social/piracy/scam stay Off.
- Review copies of screenshots are owner-only, local, `noBackupFilesDir`, 7 days / 40 max, separate
  from partner evidence (≥70%, encrypted to the partner key). Answering "not a problem" never changes
  what a partner receives.
- Strict unlock: wait **10 min**, window **15 min**, notification when it opens. PIN modes exist too.
- Capture must keep its **visible indicator**; never block uninstall; never widen the VPN routes.
- Signing: stable key, cert hash in `STATE.md`. The keystore and its password live with the owner (he
  holds `focusfence-accountable.jks` and `keystore.b64`); **never commit signing material** or put the
  password in any file here. The release workflow falls back to a throwaway key if the secrets are absent.

## What to do next (owner's order of interest)

1. His next export from 2.7.x: check `rev` answers (first real false-alarm rate), CPU per thread, and
   whether the unlock notification arrived.
2. When he can get to a computer: deploy `server/` (`server/README.md`), then first-contact test with a
   partner email and a second phone.
3. AI false-alarm cuts that cost no battery: persistence rule, combined signals
   (`docs/research/ai-detection.md` A and B).
4. Prompt to re-enable Always-on VPN when it drops; Settings grouped into cards; "Recent flags".

## Open questions only he can answer

Server hosting + domain/HTTPS, SMS provider and budget, privacy tier, log retention after upload, LICENSE,
how or whether this branch lands in his cousin's repo (all in `STATE.md` → Open decisions).
