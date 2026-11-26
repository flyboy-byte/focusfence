# Plan 16: an API for other apps and servers (scoped 2026-11-26, set aside)

**Status:** scoped only, parked. Logan: "maybe just scope that one and set it aside for now".

**The idea (Logan, 2026-11-26):** an API call to a connected server, his or anyone's: add a partner, ask someone to
become a partner, send the invite email. Another app, a website or a script could then do what the Partners screen does.

## What exists today

The app already talks to the server only through an API: JSON over HTTPS under `/v1/` (`server/README.md` lists it).

| Protection | How |
|---|---|
| Who's asking | Every phone call is signed with that phone's own P-256 key (made on the phone, never sent) |
| Replays | A signed request is good for 10 minutes |
| Partner side | Secret per-partner tokens in the report link; unguessable 8-character codes, single use, 24 h |
| Abuse | Rate limits per address and per phone; whole-server email caps; a data and device cap |
| The report | Encrypted on the phone; the server holds ciphertext and sealed keys only (D-023, D-042) |
| Admin | Email code or authenticator sign-in |

That's solid for a small server, but it isn't audited, and an operator who changes the server's code can still attack
browser partners (D-023's stated limit).

## Is an outside API a good idea?

**Partly.** The useful part is small; the risky part is most of what the idea names.

| Use | Verdict | Why |
|---|---|---|
| Make an invite (code, QR link) from elsewhere | Reasonable | Harmless: the key still goes only from the phone, at its next check-in, and the owner is told |
| Send the invite email from elsewhere | Careful | The server would become a mail relay for whoever holds the token; it already has caps, but they'd be shared |
| Read status ("protected, last check-in") | Reasonable | E.g. a counselor's dashboard; no report content |
| Remove partners, change settings, loosen | No | Undoes the point: loosening must be on the phone, through strict mode, and never silent |
| Add a partner without the phone | Impossible by design | Only the phone holds the report key; the server can't hand it out |

## If it's ever built (v1 shape)

1. **A token the phone makes**: Partners → Server → API access → "New token", with scopes (`invite`, `status`), an
   expiry, and Revoke. The server stores only its hash. Never a server-wide or admin token.
2. **Endpoints**: `POST /api/v1/invites` (same rules and caps as the app's), `GET /api/v1/status`. Nothing else.
3. **Never silent**: every API call is an owner notice on the phone ("An invite was made with your API token").
4. **Its own limits**: per token, lower than the app's; email invites off unless the token has that scope.
5. **Docs**: an `API.md` with curl examples; tokens are secrets, like passwords.

Size: about a day (server endpoints and tests half, the phone's token screen half). Not started; not on the roadmap
until someone needs it for a real integration.
