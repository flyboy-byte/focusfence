# Server admin and landing page (phases 1-3 built and deployed 2026-11-23; landing page not built)

**Phases 2-3 built (🧪 CI only, never used on the live box yet, not yet audited by the extraction session):** `Admin.kt`, `/admin` on when `FF_ADMIN_EMAILS` is set (live: Logan only). Login = emailed 6-digit code (10 min, 5 tries) + authenticator code (RFC 6238; replay refused) or a one-time recovery code; first login enrolls the authenticator (shows the key and an otpauth link, no QR) and shows 8 recovery codes once. Session cookie `__Host-` Secure/HttpOnly/SameSite=Strict, 30 min idle / 8 h, CSRF on every POST. Dashboard: usage, enrollment codes (create/revoke, shown once), devices (id/dates/partner count, remove), audit log. Sessions are in memory (a restart signs everyone out). UI is four tabs (Overview, Codes, Devices, Activity), dark mode, phone-width; extras: device names and "enrolled with code X" (`Device.codeId`/`label`), per-device storage size, code last-used, silent-phone count, test email, new recovery codes, sign out other sessions. Not done: IP allowlist, separate nginx zone for /admin, fail2ban, the landing page (phase 4).

**Phase 1 built (🧪 CI only, not deployed):** `EnrollCodes.kt` + `codes.json` in the data dir; `focusfence-server admin codes add <label> [--days N] [--uses N] | list | revoke <id>` (run with the same `FF_DATA_DIR`); `/v1/enroll` accepts a live per-person code or the bootstrap `FF_ENROLL_TOKEN`; once any code exists enrollment is closed to anyone without one. Only hashes stored; a bad key does not burn a code. Needs a deploy.

_Scoped 2026-11-23. Wanted by Logan for: onboarding other people onto his server, and giving @sidewinderzz access. "Simple and
secure, 2FA by email or an authenticator app." Reports don't need it. Don't let this grow: phases below stop early on purpose._

## What it is for
1. **Enrollment codes per person.** Today there is one shared `FF_ENROLL_TOKEN` in the env file. Giving it to a friend means
   giving it to everyone forever. The admin creates a code per person (label, optional expiry, max uses), and can revoke one.
   The app already sends the code (`X-FF-Enroll`), so **no app change**.
2. **See and tidy the server** without SSH: devices (short id, last seen, partner count, never any report content or tokens),
   remove a device, data-dir use against `FF_MAX_DATA_MB`, mailbox count, email-send counters against the daily caps.
3. **A landing page** at `/` on the ff subdomain: what this server is, that it is a blind relay for Focus Fence, how to join
   (ask the operator for an enrollment code), a link to the app release and the source. Static, no scripts, no data.

Not in scope: reading reports or alerts, editing partners, sending email on someone's behalf, user self-signup, billing.

## Security design (the part that matters)
- **Admins are an allowlist of email addresses** (`FF_ADMIN_EMAILS`), nothing else. No passwords stored anywhere.
- **Login = two factors**: (1) a one-time code emailed through the existing Resend path (10 min, single use, hashed at rest,
  per-address and per-IP limits, counted against the global email cap) **and** (2) a TOTP code from an authenticator app,
  enrolled on first login from a one-time setup screen, with 8 recovery codes shown once. Email-only is a weaker option we
  can offer per admin later; default is both. (The box's `dnsjoin` admin already runs a 2FA admin page: reuse its design.)
- Sessions: random id in an `HttpOnly; Secure; SameSite=Strict` cookie, 30 min idle / 8 h absolute, server-side, revoked on logout.
  CSRF token on every POST. The existing CSP stays (`default-src 'none'`, no inline script).
- Lockout and rate limits at app and nginx (a separate zone for `/admin`), fail2ban once the nginx-jail log path is fixed.
- **Audit log** of every admin action (who, what, when, from which IP) in the data dir; shown on the admin page.
- Admin pages can never reveal report tokens, invite codes, mailbox ids or screenshots: they are not even loaded into memory.
- Optional: an IP allowlist for `/admin`, and serving `/admin` only on a second hostname.
- Reachable at `/admin` on the same process (Docker Compose users get it too); off unless `FF_ADMIN_EMAILS` is set.

## Phases (stop after any of them)
1. **Per-person enrollment codes**, stored hashed, with label/expiry/uses/revoked, checked in `/v1/enroll`; `FF_ENROLL_TOKEN` kept
   as a bootstrap code. Managed by a small CLI (`focusfence-server admin codes ...`) so there is value before any UI. Tests.
2. **Admin login**: email code + TOTP + recovery codes + sessions + audit log. No pages yet beyond login and a status line.
3. **Admin UI**: codes (create/revoke), devices (list/remove), usage against caps, audit log.
4. **Landing page** and docs link.

## Open questions for Logan
- Who are the admins (you, @sidewinderzz, anyone else), and should email-only login ever be allowed?
- Self-hosting: should the Docker image ship with `/admin` on by default when `FF_ADMIN_EMAILS` is set, or opt-in by a flag?
- Does @sidewinderzz get his own enrollment code and admin seat, or his own server? (Affects whether this matters for the merge.)
- Landing page copy and whether it names the project or stays generic.

## Risks
A web admin on a public host is the biggest new attack surface this server would have. Phase 1 alone delivers most of the value
(per-person codes) with none of that surface; phases 2 to 4 should get the same independent audit the server just had.
