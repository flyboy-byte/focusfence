# Handoff (2026-11-27): plan 17 released as 3.1.5, a privacy fix waiting for 3.1.6

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/17-screen-text-detection.md` and
`docs/plans/15-repo-health.md` (its 2026-11-27 audit table).

## Where it stands

- **Live:** app `v3.1.5-accountable` (code 57) and `server-v2.1.0`. 119 downloads in total (README badge).
- **3.1.4:** targetSdk 35 (`ui/EdgeToEdge`). Logan: "the app looks great".
- **3.1.5, plan 17:** Logan, 2026-11-27: on-screen text "misses things" and a screenshot should have caught it too; it
  happened in Brave. A real export (analysed locally only) found the cause: after leaving Focus Fence for Brave, no
  window event came, so nothing was read for 10 minutes (fixed in #158). Also built: the word index with disguises and
  phrases (#149, #155), alerts (#150) with a quitting-page guard (#153), screenshot on words (#151), each picture
  checked on its own (#154), hot mode (#155, #156).
- **Merged after 3.1.5, not released:** #161, a privacy fix: 3.1.5's text alert carried the matched words (they can be
  site names) in server-readable alert text; now it names the app only. #162: docs current, downloads badge, CI token
  read-only, release action pinned.
- **Confidence (told to Logan):** matching and the false-alarm guard are high (tests + replay: 1 alert in 9 days); the
  blind-spot fix and hot mode are medium; per-picture checks are medium-low (not on a phone; battery unknown).

## Next, in order

1. **Release 3.1.6** for the #161 privacy fix (ask Logan once; he hasn't said yet).
2. A log export after a day on 3.1.5/3.1.6. Check: `fg_recovered` (the blind-spot fix fires), `region_runs`,
   `region_ms`, `region_src_*`, `shot_hot`, `text_hot`, `text_deep`, `text_alerts`, battery per screen-on hour (was
   10.6 %/h device, 59 s CPU/h app). Unzip into the scratchpad, replay with a throwaway test that is deleted, and put only
   counts in the repo: the auto-mode classifier blocked a commit that named apps from the export.
3. Logan's open check from 3.1.4: every screen on the Pixel (bars, keyboard).
4. Audit items still open (plan 15): A3 pin `docker/login-action`, A4 Dependabot security updates (Logan's call), A5
   toolchain (Kotlin 1.9 / Ktor 2 / TFLite 2.14), A6 crop coordinates on scaled displays.
5. Then Logan wants all of: F-Droid (plan 11), partner polish (guide page, replies, removing one group member) and push,
   **but more real testing of partners and groups first**. Ask which to start before building any.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1). An outside API (plan 16, scoped
only). Emulator test in CI (plan 15 item 4, with its trigger).
