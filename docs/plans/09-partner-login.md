# 09 — Partner login (option B), fully scoped, not built

_Scoped 2026-11-24 at @flyboy-byte's request ("A now and fully scope B"). A shipped as D-026: the invite link is the
partner's report link for good. B is the next step **only if** partners want email links that open the report
without their saved link. Nothing here is decided beyond the scope; the open questions at the bottom are Logan's._

## The problem B solves

After D-026 a partner holds one link (`/i/{secret}#k=KEY`) that works in any browser. What still doesn't "just work" is
the **link in an alert email** (`/p/{token}`): it can't carry the key, because the server sends those emails and a key
in them would let whoever runs the server read reports (D-023). So in a browser that has never opened the partner's
own link, an email link shows "the key is missing".

B adds a password, so a partner can open the report from **any email link, in any browser**, by typing it once
there, and the server still can't read anything.

## How it works

**Key wrapping, entirely in the partner's browser** (WebCrypto, the same `report.js` model as D-023):

1. On first opening their link (key present), the partner is offered "Set a password so email links work anywhere".
2. The browser derives two values from the password with **PBKDF2-SHA-256, 600 000 iterations**, salt = 16 random
   bytes made in the browser:
   - `wrapKey` (AES-256-GCM): never leaves the browser.
   - `authKey`: sent to the server as the login secret. The server stores **only an Argon2id/PBKDF2 hash of it**, so
     a stolen database doesn't give a password-equivalent.
3. The browser encrypts the report key with `wrapKey` (`wrapped = AES-GCM(wrapKey, reportKey)`, AAD
   `"ff-wrap|<partnerId>"`) and uploads `{salt, iterations, wrapped, authHash-input}`.
4. On a new browser: the email link opens `/p/{token}`, the page fetches `{salt, iterations}` for that partner,
   the partner types the password, the browser derives `authKey` + `wrapKey`, proves `authKey` to the server, gets
   `wrapped`, unwraps the report key and stores it like today. The password never leaves the browser.

**What the server holds per partner:** salt, iteration count, `wrapped` (ciphertext of a 32-byte key), a hash of
`authKey`, failure counters. **What it never holds:** the password, `wrapKey`, the report key.

## Why the operator still can't read reports

The report key is only ever in the clear inside the partner's browser (and the owner's phone). The server sees
`wrapped` and an `authKey` hash; with neither the password nor `wrapKey` it can't unwrap. **What B does not stop**,
same as D-023: an operator who changes the server to serve a malicious `report.js` that sends the password or key
away. B adds no new hole there, but it makes the password a target, so the threat model in the privacy page must say
so.

**Offline guessing:** someone with the database can try passwords against `authKey`'s hash and `wrapped`. Defences:
600k PBKDF2 iterations, a minimum length (12 characters or a 4-word passphrase), and a strength meter. This is the
same trade every password manager makes.

## Endpoints (server)

| Route | Auth | Does |
|---|---|---|
| `GET /p/{token}/wrap` | token | `{salt, iterations}` or 404 if no password set (no `wrapped`) |
| `POST /p/{token}/wrap` | token + current key in browser | set/replace `{salt, iterations, wrapped, authKey}` |
| `POST /p/{token}/unlock` | token + `authKey` | returns `wrapped` on a correct `authKey`; rate-limited |
| `POST /p/{token}/wrap/delete` | token | forget the password (back to link-only) |

Rate limits: 5 failed unlocks per partner per hour, 20 per day; 30 per client IP per hour. Lockouts are per
partner, so a stranger with the token can't lock the partner out forever: after a day it resets, and the partner's
own link (D-026) always works regardless.

## Phone side

Nothing has to change. The key the phone made (`SyncStore.reportKey`) is what gets wrapped. If the owner **rotates
the key** (a future "start over" action), every wrapped copy is stale; the page detects that (unwrap works but
decrypt fails) and asks the partner to open their link again, which re-wraps.

## Recovery

A forgotten password can't be recovered, by design. The partner opens their own link (D-026), which still works and
holds the key, and sets a new password. If that link is lost too, the owner sends a new invite.

## UI

- Report page, when the key is present and no password is set: a small card "Open email links on any device" →
  set password (twice) with a strength meter.
- Report page, when the key is missing and a password exists: a password box instead of "the key is missing".
- Report page settings: "Change password", "Forget password on the server".
- Email footer text: "Opens with your link, or your Focus Fence password."

## Tests

- Kotlin server: set, unlock success, wrong `authKey` → 401 with counters, lockout and reset, delete, payload limits
  (`wrapped` ≤ 128 bytes, salt 16, iterations 300k–2M).
- `report.js` against Node WebCrypto, like the D-023 cross-check: wrap in one "browser", unwrap in another, decrypt a
  real sealed slice.
- A database-only attacker test: given the stored row, show that decrypting a slice needs the password.

## Size

About 1.5–2 days: server routes and storage (½ day), `report.js` crypto and UI (1 day), tests and privacy page (½ day).
No app release needed; it's server-only, so it ships with a deploy.

## Open questions (Logan's)

1. **Worth it at all?** With D-026, B only helps a partner who taps email links on a device where they never opened
   their own link. If partners just use their link, B can stay on the shelf.
2. **Password, or passkey?** A passkey (WebAuthn PRF extension) would avoid typing a password, but PRF support is
   still patchy across browsers in 2026; a password works everywhere. Recommendation: password first.
3. **Optional or required?** Recommendation: optional, offered on the report page; never required to receive alerts.
