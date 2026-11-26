# Plan 14: groups on the plan 13 partners (started 2026-11-26)

**Status:** built 2026-11-26 on `feat/groups`, not released. Mockups (screens 9–13 on the plan 13 canvas,
https://claude.ai/artifact/QiKPntgDHtLJrGSS3JBLco) approved by Logan: "Those look great".

**Why:** plan 13 cut the old groups (they ran on pairing keys and mailboxes, both gone). Logan, 2026-11-26, after the
first 3.0.0 test: real groups, not just mutual pairs ("Real groups is the I'm leaning towards"). No user accounts
("Keep without accounts for now").

## Logan's answers (2026-11-26, do not re-ask)

| Question | Answer |
|---|---|
| Size | **Up to 6** people |
| Who gets in | **Anyone with the code**, like plan 13's invites. Members are told "Sam joined the group" |
| Mutual | **Yes, all mutual**: joining means you watch them and they watch you. A watch-only person (a mentor) is a normal partner instead |
| Groups per phone | **Several** |

## The design: a group is a set of plan 13 partner pairs

Nothing new travels the wire for reports, keys or alerts. A group only decides who becomes whose partner.

1. **Start a group:** Partners → Groups → Start a group → name it ("Tuesday group"). The phone shows a code and QR,
   the same 8-character format as a partner invite. It works for 24 hours, for as many people as fit.
2. **Join:** another phone types or scans the code (the same Join box as a partner invite: the server says which kind).
   It must be an app phone, since every member is watched; joining enrolls it on that server (PR #123).
3. **The server pairs them both ways:** for each member already in the group, it adds the joiner as that member's
   partner, and that member as the joiner's partner. These are ordinary `Partner` records marked with the group id.
4. **Keys arrive as in plan 13:** at the next heartbeat each phone hears of its new pairs. As a reader, it makes a
   device key for each new report and registers it (the existing add-device path). As an owner, it seals its report
   key to each new reader (`seal[]`, `/v1/seal`). The fast poll (1/min for 30 min) runs while a group code is open.
5. **Leave a group:** removes every pair with that group's id, both ways, and rotates the leaver's report key and that
   of each remaining member. Members are told. The last member leaving deletes the group.

## Defaults I picked (each changeable)

| Choice | Default | Why |
|---|---|---|
| Cap per phone | 5 private partners, plus up to **4 groups**; at most **20** people watching one phone in all | Several groups of 6 would blow past the plan 13 cap of 5 |
| Same person in two groups, or a group member who is also a private partner | One pair, kept while any reason for it exists | Otherwise they'd get every alert twice |
| Alerts from a group | The reader's own alert setting per person (plan 12 choices), defaulting to "Important only" for group pairs | 5 people × several groups is a lot of "all" |
| Ask a partner (unlock) | Any group member can answer, like any partner | Same rule as plan 13 |
| Who can make a new code | Any member, while the group has room | No group admin to lose |
| Removing someone | Not in v1: a member leaves on their own; to drop someone, the others start a new group | Removing needs an owner or a vote; leave it out until it's missed |
| Browsers / email-only people | Can't join a group | A group member must be watchable |

## Screens to mock up

1. Partners, with a **Groups** section (each group: name, members, "Add someone").
2. Start a group (name, then code + QR).
3. Join: the code box says "Tuesday group · 3 people: Logan, Sam, Jo" before you confirm.
4. A group's page: members, what each sees, Leave group.
5. The notice "Sam joined Tuesday group".

## Size

About a day of building after the mockups: server (group record, join, leave, caps, tests) half a day; app (Groups
section, start/join/leave, reader keys for group pairs) half a day. Protocol level stays 2 (new endpoints only). Joining
a group needs the new app; an older member would see the new people as partners but couldn't read their reports, so
the group code refuses phones below that version.

## Build (2026-11-26)

| Piece | Where |
|---|---|
| Group record, members, one open code | `server/.../Store.kt` (`Group`, `groups/` dir), `Partner.memberDeviceId` / `groupIds` |
| Join, leave, pairs, caps, list, alert level, member keys | `server/.../Groups.kt`; routes `/v1/groups/*`, `/v1/codes/lookup` in `Main.kt` |
| Heartbeat | `groupPairs` (reports to send a key for), `groupsBusy` (check in every minute), `SealTarget.groupName` |
| Enrolling | `X-FF-Group: <code>` enrolls a phone with no enrollment code; credentialed enrolls have their own looser rate limit (a meeting is six phones on one Wi-Fi) |
| QR code | `/c/{code}` shows the group and "Open in the Focus Fence app" (no browser join) |
| App | `sync/Accountability` (lookup, groups, create, code, join, leave, level), `SyncWorker.groupPairs`, `GroupActivity`, `GroupUi`, Groups card on Partners |

Changes from the plan while building:
- **Leaving or removing one pair of a group** is refused (409): Remove and Stop seeing say "leave the group" instead.
- **The older-app guard** wasn't needed: joining uses new endpoints, so only a new app can join.

**Verified:** server tests (59, 4 new in `GroupsTest`: pairs both ways and keys, two groups one pair, six and full,
enroll by group code and the QR page). App unit tests, assembleDebug and lint pass.
**On phones (2026-11-26):** Logan used a group on 3.1.x: "i just used group and it looks amazing". He asked for a Scan a
QR code button (opens the camera app; 3.1.2) and codes with or without the dash (already so; now tested on the server).
