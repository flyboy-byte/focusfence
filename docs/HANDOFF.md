# Handoff (2026-11-26): plan 13 released, not yet tried on a phone

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/13-partners-redesign.md` (design, Logan's
answers, the build table) and `DECISIONS.md` D-042.

## Where it stands

- **Production is paused.** The live server (1.3.0) was wiped on 2026-11-26 at 03:33 UTC (admin login and codes kept;
  backup `/var/backups/ffbackend-data-20261126.tgz`, root-only). Both phones show sync errors: expected.
- **Plan 13 is built** on `feat/partners-v2-server` (PR to main). One kind of partner; invites by code, QR code or email;
  each partner device gets the report key sealed to its own key by the owner's phone; protocol level 2 with a floor of 2,
  so level-1 apps get 426. Old partner data is deleted at start on both sides.
- **Released 2026-11-26:** app `v3.0.0-accountable` (code 51) and `server-v2.0.0`, deployed 04:37 UTC (backups and log in
  the VPS hardening log). Phones enrolled before the wipe get "unknown device" and reopen Server: a new enrollment code
  is needed (admin page → People).
- **Prototype (design canvas, all 8 screens):** https://claude.ai/artifact/QiKPntgDHtLJrGSS3JBLco. Logan: "i think i
  like that". The screen files were only in a session scratchpad; read them back with Artifact `read` and `path`.

## Tested

| What | How | Result |
|---|---|---|
| Server | `./gradlew -p server test` (54, incl. `CompatLevel2Test`) | pass |
| App | `testDebugUnitTest assembleDebug lint` | pass, 0 lint errors |
| Crypto interop | Java `Ecies` seals, browser WebCrypto code (copied from `report.js`) opens | pass |
| Browser end to end | headless Chrome, local server, a Node script as the owner's phone | join by code → waiting → key sealed → report opens; second browser offered "Add this browser" |
| App on a phone | — | **not done** (no emulator here) |

## Next, in order

1. ~~Merge, release server 2.0.0 and app 3.0.0~~ DONE (2026-11-26).
2. Logan tests on both phones:
   - add a partner by code and by email;
   - join from the second phone;
   - open the report in a browser;
   - remove and leave.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope), groups (scope).
