# Focus Fence accountability server (prototype)

**Status: small-group server, built and tested by CI** (`./gradlew -p server test` from the repo root).
Running for one person since 2026-11-23, after an independent security audit; not load-tested. Flat-file storage:
fine for a person or a few, not for many.

It compiles the phone's pure `app/src/main/java/app/focusfence/dns/` code too, so the server
alerts on exactly what the phone's `Digest.alertText` alerts on, and the partner web page shows
the same `Digest` the partner app does. One rule set for both ends.

## One invite, any partner (D-035)

Every phone on the server is enrolled with a hardware-backed P-256 key. The phone **never uploads its activity log**
(D-023): it builds the partner report on the phone, seals it with a key only it and its partners hold, and uploads the
ciphertext hourly, plus signed alert lines and heartbeats.

**Getting a phone on.** The admin page's *People* tab makes an enrollment code and a message to send; the person pastes
it into *Partners → Join with an invite*. A partner's phone needs no code: the invite it was sent is its credential.

**Adding a partner.** The owner's phone asks for an invite (24 h, single use, max 5 live) and shares one link,
`/i/SECRET#k=REPORT_KEY&p=PAIRING_KEY`. Browsers never send the part after `#`.
- **In a browser:** an email partner. They join with name and email, confirm the address (double opt-in), and get alert
  emails, a weekly report, a private report page at `/p/{token}` (the link is their login), and optional SMS alerts
  (opt-in, code-verified, throttled to one per 10 min).
- **In the app:** the phone enrolls with SECRET, takes the invite (`/v1/invites/accept`) and gets its own `/p/{token}`:
  the same page, sealed data and screenshots as an email partner. The server records the pair. Alerts and the rotating
  unlock code (accepted offline by the owner's phone as the partner PIN) go through a pairing mailbox derived from
  PAIRING_KEY: ciphertext under an id, with only a hash of the access token stored.

**Groups** (up to 4 phones) use one shared mailbox for the group log; a group invite carries an enroll-only server
invite so new members' phones can enroll. Only enrolled phones open new mailboxes.

**Admin page** (`FF_ADMIN_EMAILS`): sign in with an emailed code, or with an authenticator code once one is set up.
Tabs: Overview, People (add a person: code + message), Devices (who watches whom), Activity (admin log, sessions,
your sign-in). On the box, `focusfence-server admin login-link <email>` prints a one-time sign-in link.

## Start to finish

What you need: a Linux server you can reach from the internet, a domain name pointing at it, and for email partners
either a [Resend](https://resend.com) account with that domain verified (easiest) or any SMTP account. App partners
work without email.

1. **Install** with Docker Compose or the systemd tarball (below). Set `FF_PUBLIC_URL` to your `https://` address.
2. **HTTPS:** the `https` compose profile (Caddy) or `nginx.conf.example`. The app only talks to `https://`.
3. **Check it:** `https://your.domain/healthz` answers `ok`.
4. **Connect your own phone:** create an enrollment code and paste what it prints into the app (Partners → *Join with an
   invite*). systemd: `sudo ./ff-admin.sh admin codes add me --uses 1`. Docker:
   `docker compose exec server /opt/focusfence-server/bin/focusfence-server admin codes add me --uses 1`.
   Or turn on the admin page (`FF_ADMIN_EMAILS`) and use its People tab.
5. **Add a partner** from the app (Partners → *Invite a partner*). Share the link: opened in a browser it's an email
   partner, pasted into their Focus Fence it's an app partner.

## Run with Docker Compose (recommended)

```sh
cd server/deploy
cp .env.example .env          # set FF_PUBLIC_URL, RESEND_API_KEY, FF_MAIL_FROM (and FF_DOMAIN for HTTPS)
docker compose up -d                    # server on 127.0.0.1:8080 only: put your own TLS proxy in front
docker compose --profile https up -d    # or let Caddy fetch a certificate for FF_DOMAIN on ports 80/443
docker compose up -d --build            # build from this checkout instead of pulling the published image
```

The image is `ghcr.io/<owner>/focusfence-server` (linux/amd64 and arm64), published with each `server-vX.Y.Z` tag
together with a tarball that includes this compose file. The container runs as a non-root user on a read-only
filesystem with all capabilities dropped; data lives in the `ff-data` volume. `GET /healthz` answers `ok` for
health checks. The Android app only talks to `https://` servers, so a real deployment needs the `https` profile
or your own TLS proxy. Whatever sits in front must send `X-Forwarded-For`, and its address must be inside
`FF_TRUSTED_PROXIES` (default: loopback, plus the private ranges in the compose file), or every client shares one
rate-limit bucket.

Back up the data volume (it holds every partner's report token and the activity records):

```sh
docker run --rm -v focusfence_ff-data:/data -v "$PWD":/backup alpine tar czf /backup/ff-data-$(date +%F).tgz -C /data .
```

Tested: the image builds, passes its health check, runs read-only as non-root, and buckets rate limits by real
client address behind a proxy on the Docker bridge. The compose file was brought up end to end from source, with
and without the `https` profile: Caddy serves HTTPS with the security headers, plain HTTP redirects, and the data
volume survives a restart. Caddy getting a real public certificate wasn't tested (it needs a public domain).

## Run with systemd (no Docker)

Each `server-vX.Y.Z` release also has a tarball with an installer. It needs a JRE 17 or newer on the host.

```sh
v=1.1.0   # the newest server-v* release: https://github.com/flyboy-byte/focusfence/releases
f=focusfence-server-$v.tar.gz
base=https://github.com/flyboy-byte/focusfence/releases/download/server-v$v
curl -fLO $base/$f && curl -fLO $base/$f.sha256
sha256sum -c $f.sha256 && tar xzf $f && cd focusfence-server-$v
sudo ./install.sh        # first run: creates the user, unit and /etc/focusfence-server/env
sudo nano /etc/focusfence-server/env
sudo systemctl start focusfence-server
```

For a later version, unpack it and run `sudo ./install.sh` again. It keeps the old copy, restarts, checks
`/healthz`, and puts the old copy back by itself if the new one doesn't come up. `sudo ./install.sh --rollback`
goes back by hand. The server listens on `127.0.0.1:8080`; `nginx.conf.example` in the tarball is a working
front end (TLS, rate limits, no access log). Admin commands: `sudo ./ff-admin.sh admin codes list`.

Tested in a Debian 12 container: first install, upgrade, a broken upgrade rolled back automatically, and a manual
rollback.

## Run from source

```sh
# from the repo root
FF_PUBLIC_URL=https://ff.example.com RESEND_API_KEY=... FF_MAIL_FROM=reports@example.com \
  ./gradlew -p server run
# no RESEND_API_KEY → emails print to stdout; FF_SMS_PROVIDER=dev → texts print too
```

| Env | Default | |
|---|---|---|
| `FF_PUBLIC_URL` | `http://localhost:8080` | Base for links in emails and invites |
| `FF_HOST` | `0.0.0.0` | Address to bind. Use `127.0.0.1` behind a proxy on the same host |
| `FF_PORT` | `8080` | |
| `FF_TRUSTED_PROXIES` | loopback | Comma list of IPs / IPv4 CIDRs allowed to set `X-Forwarded-For` |
| `FF_MAX_DEVICES`, `FF_MAX_MAILBOXES`, `FF_MAX_DATA_MB` | `100`, `500`, `2048` | Hard bounds on what unauthenticated enrol/mailbox creation can use |
| `FF_DATA_DIR` | `./data` | Flat-file store |
| `FF_STALE_MINUTES` | `60` | Silence before "phone went silent" (phones check in about every 15 min). Email, text and app partners all get it, and a "checking in again" message when the phone is back. |
| `FF_ENROLL_TOKEN` | — | Optional bootstrap enroll code; more codes come from `admin codes` |
| `FF_ADMIN_EMAILS` | — | Comma list. Turns on the admin page for these addresses; off when empty. Sign in with an emailed code, or with an authenticator code once one is set up (Activity tab): either one, not both |
| `RESEND_API_KEY`, `FF_MAIL_FROM` | — | Email via Resend (preferred) |
| `FF_SMTP_HOST`, `FF_SMTP_PORT`, `FF_SMTP_USER`, `FF_SMTP_PASSWORD`, `FF_SMTP_TLS` | —, `587`, —, —, `starttls` | Email over plain SMTP instead (Gmail app password, Fastmail, your own server), used only when no Resend key is set. `FF_SMTP_TLS`: `starttls`, `ssl` (port 465) or `none` (a local relay only) |
| `FF_SMS_PROVIDER` | `none` | `twilio`, `telnyx`, or `dev` |
| `FF_SMS_FROM` | — | Sending number |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | — | For `twilio` |
| `TELNYX_API_KEY` | — | For `telnyx` |

Put it behind HTTPS (Caddy or nginx). Android blocks plain-HTTP connections from the app by
default, so the phone can only talk to an `https://` server.

## Endpoints

Device requests are signed (`action|deviceId|t|extra`, ECDSA P-256) and must be within 10 minutes
of server time.

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/enroll` | Register a device key → `deviceId`. Needs `X-FF-Enroll` (a code) or `X-FF-Invite` (a live invite) when codes are on |
| POST | `/v1/invites` | Signed. New one-time invite link (24 h, max 5 live); `kind: enroll` for a group's members |
| POST | `/v1/invites/accept` | Signed. The calling phone takes an invite and becomes that phone's app partner; returns its report link |
| POST | `/v1/partners/list` | Signed. Email partners (masked) and app partners, each with the invite it came from |
| POST | `/v1/partners/unlink` | Signed. Remove a partner — **they are emailed/texted** |
| POST | `/v1/batches` | Signed batch: seq + hash chain + signature checked; 409 returns the server cursor |
| POST | `/v1/heartbeat` | Signed liveness |
| POST | `/v1/shots` | Signed encrypted screenshot (ciphertext only) |
| POST | `/v1/mailbox/{id}` | Create a mailbox with a token hash; a new one needs a signed request from an enrolled phone |
| POST/GET | `/v1/mailbox/{id}/msgs` | Post / read ciphertext (`X-FF-Token` header) |
| GET | `/i/{secret}` | The invite link: join form, later the partner's report |
| GET/POST | `/join` | Partner joins with an invite |
| GET | `/p/{token}` | Partner's private report page |
| GET | `/p/{token}/data` | The sealed report slices (ciphertext) the page opens |
| GET | `/p/{token}/alerts?after=T` | What this partner has been told (alert lines, silence, back online) plus `online`; app partners poll it |
| POST | `/p/{token}/sms`, `/sms/verify`, `/sms/stop`, `/leave` | Partner settings |

## Not done

- Flat files; backups are yours (copy the data directory or volume).
- Join rate-limiting is per client IP, in memory; behind a reverse proxy it needs the real IP.
- Screenshots: the endpoint exists, but the phone doesn't upload them yet.
- Report tokens are kept in the clear (so alert emails can link to the report).
