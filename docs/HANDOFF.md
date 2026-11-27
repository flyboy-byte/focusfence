# Handoff (2026-11-27): 3.1.6 released (detection tuning, flag sensitivity)

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/17-screen-text-detection.md` and
`docs/plans/15-repo-health.md` (its 2026-11-27 audit table).

## Where it stands

- **Live:** app `v3.1.6-accountable` (code 58) and `server-v2.1.0`. 119 downloads in total (README badge).
- **3.1.4:** targetSdk 35 (`ui/EdgeToEdge`). Logan: "the app looks great".
- **3.1.5, plan 17:** screen text and screenshots: word index with disguises and phrases (#149, #155), alerts (#150)
  with a quitting-page guard (#153), screenshot on words (#151), each picture checked on its own (#154), hot mode
  (#155, #156), and a fix for a blind spot after leaving Focus Fence (#158; confirmed on a phone).

## 3.1.6 (2026-11-27, code 58)

Tuning from a local export analysis: secret fields (#164), text alerts name the app only (#161), erotica words and the
quitting guard by distance (#165), pictures next to explicit words (#166), body check from 0.30 and the female boost
(#167), Flag sensitivity 0.45 / 0.50 / 0.70 (#168).

**Public-repo rule:** code, docs, tests, commits and PRs describe what the app does, never what the owner viewed, said
about it, or prefers. No times, apps, page descriptions or alert counts from an export; test strings are made up.

## Next, in order

1. Real use of 3.1.6 (ask once), then an export. Check `flag_context`, `flag_suggestive`,
   `body_female`, `body_runs`, `text_secret_skipped`, `text_alerts`, battery.
2. If low-scoring pictures still slip by: run the body check on every picture while in context (more battery,
   only in those 3-minute windows). Offered, not asked for.
3. Logan's open check from 3.1.4: every screen on the Pixel (bars, keyboard).
4. Audit items still open (plan 15): A3 pin `docker/login-action`, A4 Dependabot security updates (Logan's call), A5
   toolchain, A6 crop coordinates on scaled displays.
5. Then F-Droid (plan 11), partner polish, push: ask which first, after more real testing.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1). An outside API (plan 16, scoped
only). Emulator test in CI (plan 15 item 4, with its trigger).
