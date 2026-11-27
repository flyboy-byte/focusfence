# Handoff (2026-11-26): groups released, not yet tried on phones

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/14-groups.md` (Logan's answers, build
table), `docs/plans/13-partners-redesign.md` and `DECISIONS.md` D-042.

## Where it stands

- **Live:** app `v3.1.2-accountable` (code 54; 3.1.0 had groups) and `server-v2.1.0`, deployed 2026-11-26 15:39 UTC. Backups taken first; paths in the VPS hardening log.
- **Logan's first 3.0.0 test:** "I love it, it was quick and seem less". Found: the second phone said "Not set up" though
  it got reports. Fixed in 3.1.0 (PR #123): joining a report enrolls the phone on that server with its partner token
  (`X-FF-Partner`, one phone per partner). Logan's model: two kinds of phone, on a server or not.
- **Groups (plan 14, PR #125):** up to 6, anyone with the code, all mutual, several per phone (4 max), 20 watchers per
  phone. Pairs both ways; a pair two groups (or a group and an invite) made is one pair. Group pairs aren't removed one at
  a time: leaving the group is how.
- **Accounts:** none, decided (Logan: "Keep without accounts for now").
- **Prototype canvas (13 screens, groups are 9–13):** https://claude.ai/artifact/QiKPntgDHtLJrGSS3JBLco. Logan: "Those
  look great".

## Tested

| What | Result |
|---|---|
| Server tests (59, incl. `GroupsTest` ×4, `CompatLevel2Test`) | pass |
| App `testDebugUnitTest assembleDebug lint` | pass, 0 lint errors |
| Live `/v1/version` | protocol 2, oldest 2, server 2.1.0 |
| Groups on phones | Logan, 2026-11-26: "i just used group and it looks amazing" |

## Next, in order (Logan's answers, 2026-11-26)

1. Production pause lifted. Logan tried 3.1.2; 3.1.3 released with plan 15 items 1, 2, 3, 5 (item 4 parked).
2. Plan 15 item 7, targetSdk 35: steps 1-3 merged 2026-11-27 (#143 compileSdk 35 + AGP 8.7.3, #144 libraries,
   #145 targetSdk 35 + `ui/EdgeToEdge`). Released as 3.1.4 (code 56, 2026-11-27). Step 4 open: Logan checks every screen on
   the Pixel (bars not overlapping, keyboard not covering a field). targetSdk 35 also clears one F-Droid prerequisite.
3. After that, Logan wants all of: F-Droid (plan 11), partner polish (guide page, replies, removing one group member) and
   push, **but more real testing of partners and groups first**. Ask which to start before building any.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1). An outside API (plan 16, scoped only).
