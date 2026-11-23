# Server admin and landing page (scope only; nothing built)

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
