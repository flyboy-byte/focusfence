# Handoff (2026-11-26, early morning UTC): paused for the partner redesign

Replaces the previous handoff. Read with `STATE.md` (status block) and `docs/plans/13-partners-redesign.md`.

## Where it stands

- **Production is paused** (Logan: "hard pause the whole app and go through some iterations and testing with updates
  before we start back into production").
- **The live server was wiped on 2026-11-26 at 03:33 UTC:** devices, mailboxes and screenshots. Kept: `admin.json`,
  `admin-audit.jsonl`, `codes.json`. Backup (root, 600): `/var/backups/ffbackend-data-20261126.tgz` on the VPS. Both
  phones show sync errors until the new partner system ships; that's expected, and the filter is unaffected. Logged in
  `~/security/hardening-log.md`.
- **Released and live:** app `v2.26.0-accountable` (code 50) and server `server-v1.3.0` (live `/v1/version` = 1.3.0).
- **Merged after 2.26, not released:** #113 (Remove always in a seen report's menu, dead links flagged, "protection
  off" names the days), plus docs PRs #112, #114 and #115.

## Next, in order

1. **Plan 13 design.** Logan's answers are in the plan:
   - all three invite methods (email, code of at most 8 characters, QR code);
   - no Allow step: whoever uses the invite first is trusted, and the owner's phone seals the key to that device by
     itself;
   - groups and SMS as scope notes only;
   - normal releases while paused.

   Two questions are still open: (1) unlock codes read aloud go away in favour of ask-a-partner, OK? (2) mockups as a
   page or as sketches.
2. **The clickable prototype**, a Design canvas: https://claude.ai/artifact/QiKPntgDHtLJrGSS3JBLco. `canvas.json` lists
   8 phone artboards (390×844) in three rows: you, an app partner, an email partner. **Only `Main.dc.html` (1 · Your
   Partners screen) is written and published.** Still to write, one file per publish call:
   - `OwnerAdd.dc.html`: name, then email / show code `K7Q4-M2XD` / QR;
   - `OwnerJoined.dc.html`: "Sam joined";
   - `PartnerJoin.dc.html`: type the code or scan;
   - `PartnerWaiting.dc.html`: "Waiting for Logan's phone…";
   - `PartnerReport.dc.html`: report, how alerts reach you, Stop seeing;
   - `EmailInvite.dc.html`: the email, no key in it;
   - `WebJoin.dc.html`: Open in the app, or continue in the browser.

   The working copy is in the session scratchpad (`proto/project/`), which may be gone after a reboot. Read the
   canvas files back with Artifact `read` and the `path` option.
3. **Then build plan 13 as a clean cut:** protocol level 2 with `oldestApp = 2`; the server drops pairing mailboxes,
   keyed `/i/` invites and the second feed path; the app wipes old partner data on first start; old compat suites go
   with their paths.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope), groups (scope).
