# Handoff (2026-11-26): groups released, not yet tried on phones

Replaces the previous handoff. Read with `STATE.md` (status block), `docs/plans/14-groups.md` (Logan's answers, build
table), `docs/plans/13-partners-redesign.md` and `DECISIONS.md` D-042.

## Where it stands

- **Live:** app `v3.1.1-accountable` (code 53; 3.1.0 had groups) and `server-v2.1.0`, deployed 2026-11-26 15:39 UTC. Backups
  `/etc/ffbackend/env.bak-20261126-1539`, `/var/backups/ffbackend-data-pre2.1.0-20261126-1539.tgz`; logged in the VPS
  hardening log.
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

## Next, in order

0. Plan 15 (repo health, `docs/plans/15-repo-health.md`): scoped from the assessment, not started.

1. Logan tests on both phones: update; Pixel Partners → Start a group; second phone Join with a code or scan; each sees
   the other's report within ~2 min; leave on one, both stop.
2. Fix what that finds; unpause production after a good test.

## Parked (don't build unless Logan asks)

Replies to alerts, a partner guide page (closed PR #108), push (notes in plan 12), the partner UI options (plan 12),
F-Droid (plan 11), SMS (Twilio scope). Removing someone from a group (plan 14: not in v1).
