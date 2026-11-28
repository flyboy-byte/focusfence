# Handoff (2026-11-27): 3.1.8 released (apps that skip the filter); new workflow

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/17-screen-text-detection.md` and
`docs/plans/15-repo-health.md` (its 2026-11-27 audit table).

## Where it stands

- **Live:** app `v3.1.8-accountable` (code 60) and `server-v2.1.0`.
- **3.1.4:** targetSdk 35 (`ui/EdgeToEdge`). Logan: "the app looks great".
- **3.1.5, plan 17:** screen text and screenshots: word index with disguises and phrases (#149, #155), alerts (#150)
  with a quitting-page guard (#153), screenshot on words (#151), each picture checked on its own (#154), hot mode
  (#155, #156), and a fix for a blind spot after leaving Focus Fence (#158; confirmed on a phone).

## 3.1.8 (2026-11-27, code 60): apps that skip the filter

The list dialog passed no click listener, so ticks were dropped (#176); a locked strict mode refused the restart that
applies them; and the restart's gap could be recorded as the filter stopping, which alerts partners
(`dns/RestartGrace`). Not tried on a phone. Same release: docs current with the app, and D-043.

## 3.1.7 (2026-11-27, code 59): logs locked

`crash` and `exit` records, `net` changes, DNS failure reasons (`fwd_err_*`), update and report-page errors,
connection state in the export header, and an "app & connection problems" section first in both summaries (#174). The
format is add-only (`LogSchemaTest`, rules and a symptom table in `docs/specs/event-log.md`). Work goes to detection,
accountability and feel; logging changes only to add a counter or error for something new.

## 3.1.6 (2026-11-27, code 58)

Tuning from a local export analysis: secret fields (#164), text alerts name the app only (#161), erotica words and the
quitting guard by distance (#165), pictures next to explicit words (#166), body check from 0.30 and the female boost
(#167), Flag sensitivity 0.45 / 0.50 / 0.70 (#168).

**Public-repo rule:** code, docs, tests, commits and PRs describe what the app does, never what the owner viewed, said
about it, or prefers. No times, apps, page descriptions or alert counts from an export; test strings are made up.

## How changes land now (D-043)

Ordinary changes go straight to `main` after `tools/check.sh`; PRs for server, protocol, encryption, updater, signing,
workflows and big changes. Table and release steps: `AGENTS.md`.

## Next, in order

1. Try 3.1.8 (ask once): tick apps to skip the filter, tap OK, reopen the list.
2. Real use, then an export. Read its "app & connection problems" section, then check `flag_context`,
   `flag_suggestive`, `body_female`, `body_runs`, `text_secret_skipped`, `text_alerts`, battery.
3. Screen checks → Advanced still has an old pasted "partner key" that saves encrypted pictures nothing sends.
   Removing it was proposed; no answer yet.
4. A dry run with a real partner: turn the filter off and time the alert; leave the phone unplugged overnight and see
   whether "went silent" arrives; ask for an OK from strict mode.
5. If low-scoring pictures still slip by: run the body check on every picture while in context (more battery,
   only in those 3-minute windows). Offered, not asked for.
6. Logan's open check from 3.1.4: every screen on the Pixel (bars, keyboard).
7. Audit items still open (plan 15): A3 pin `docker/login-action`, A4 Dependabot security updates (Logan's call), A5
   toolchain, A6 crop coordinates on scaled displays.
8. Then F-Droid (plan 11), partner polish, push: ask which first, after more real testing.

## Assessment at handoff (2026-11-27)

- **Solid:** `main` clean and CI green, 3.1.8 APK checked (version, signing cert), server 2.1.0 healthy, no open PRs
  or issues.
- **Unproven:** 3.1.6, 3.1.7 and 3.1.8 have not been checked on a phone. First look: the app opens, the filter runs,
  the update installed, apps that skip the filter stay ticked. Don't stack more releases on these before that.
- **Housekeeping:** two branches from closed PRs remain on GitHub (`chore/release-3.1.2`, #131; `journey-3`, #95),
  each with commits not on `main`. Delete only if Logan says.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1). An outside API (plan 16, scoped
only). Emulator test in CI (plan 15 item 4, with its trigger).
