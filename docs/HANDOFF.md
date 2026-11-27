# Handoff (2026-11-27): 3.1.6 released (detection tuning, flag sensitivity)

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

## 3.1.6 (2026-11-27, code 58)

An export of 3.1.5 (analysed locally, deleted after) showed the blind-spot fix working (longest unread gap 53 s), a
password captured from a revealed web field (#164), a story page whose tags weren't listed and whose one recovery word
silenced it (#165), and covers at 0.41 under every bar (#166, #167). Logan picked Flag sensitivity bars
0.45 / 0.50 / 0.70 (#168). Don't describe the owner's own browsing in public files: the classifier blocks it.

## Next, in order

1. Logan retests the same kind of page on 3.1.6 (ask once), then an export. Check `flag_context`, `flag_suggestive`,
   `body_female`, `body_runs`, `text_secret_skipped`, `text_alerts`, battery.
2. If covers scored under 0.25 still slip by: run the body check on every picture while in context (more battery,
   only in those 3-minute windows). Offered, not asked for.
3. Logan's open check from 3.1.4: every screen on the Pixel (bars, keyboard).
4. Audit items still open (plan 15): A3 pin `docker/login-action`, A4 Dependabot security updates (Logan's call), A5
   toolchain, A6 crop coordinates on scaled displays.
5. Then F-Droid (plan 11), partner polish, push: ask which first, after more real testing.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1). An outside API (plan 16, scoped
only). Emulator test in CI (plan 15 item 4, with its trigger).
