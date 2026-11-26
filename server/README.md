# Focus Fence accountability server (prototype)

**Status: small-group server, built and tested by CI** (`./gradlew -p server test` from the repo root).
Running for one person since 2026-11-23, after an independent security audit; not load-tested. Flat-file storage:
fine for a person or a few, not for many.

It compiles the phone's pure `app/src/main/java/app/focusfence/dns/` code too, so the server
alerts on exactly what the phone's `Digest.alertText` alerts on, and the partner web page shows
the same `Digest` the partner app does. One rule set for both ends.

## One kind of partner (plan 13, protocol 2)

Every phone being watched is enrolled with a hardware-backed P-256 key. The phone **never uploads its activity log**
(D-023): it builds the partner report on the phone, seals it with a report key only it and its partners' devices hold,
and uploads the ciphertext, plus signed alert lines and heartbeats.

**Getting a phone on.** The admin page's *People* tab makes an enrollment code and a message to send; the person pastes
it into *Partners → Join with a code* (or types the server and code under *Server*). Partners need no code.

**Adding a partner.** The owner's phone makes an invite (24 h, single use, max 5 live): an 8-character code, the same
code as a QR code of its link (`/c/CODE`), and optionally an email to the partner with a link of its own (`/j/SECRET`).
Whoever uses it first joins: in the Focus Fence app (by code, or "Open in the app" on the invite page) or in a browser.
- **The key never travels in a link.** The joining device makes its own P-256 key pair and sends only the public half.
  The owner's phone sees it in its next heartbeat (`seal`), seals the report key to it (ECIES, `dns/Ecies`), and posts
  the ciphertext (`/v1/seal`). While an invite is open the phone checks in every minute, so it takes about one.
- **More devices** (a laptop browser later): opening the report link on a new browser offers "Add this browser". The
  owner's phone says "Sam added a device", and Sam is emailed if they joined by email.
- **Alerts** go to the partner's app while it reads the feed (`/p/{token}/alerts`), otherwise by email when they joined
  through the emailed link (which proves the address). A browser joined by code gets the report, not alerts.
- **Removing** a partner, or a partner leaving or dropping a device, makes the phone start a new report key.

Apps at protocol level 1 (pairing mailboxes, keyed `/i/` links, email double opt-in, SMS, groups) get HTTP 426 and an
"update the app" message. Their partner data was deleted, not converted (`Store.migrateToV2`, at every start).

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
v=1.2.0   # the newest server-v* release: https://github.com/flyboy-byte/focusfence/releases
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
# no RESEND_API_KEY → emails print to stdout
```

| Env | Default | |
|---|---|---|
| `FF_PUBLIC_URL` | `http://localhost:8080` | Base for links in emails and invites |
| `FF_HOST` | `0.0.0.0` | Address to bind. Use `127.0.0.1` behind a proxy on the same host |
| `FF_PORT` | `8080` | |
| `FF_TRUSTED_PROXIES` | loopback | Comma list of IPs / IPv4 CIDRs allowed to set `X-Forwarded-For` |
| `FF_MAX_DEVICES`, `FF_MAX_DATA_MB` | `100`, `2048` | Hard bounds on enrolled phones and data |
| `FF_DATA_DIR` | `./data` | Flat-file store |
| `FF_STALE_MINUTES` | `60` | Silence before "phone went silent" (phones check in about every 15 min). Partners get it by app or email, and a "checking in again" message when the phone is back. |
| `FF_ANDROID_PACKAGE` | `app.focusfence.accountable` | The app the invite page's "Open in the app" button opens; only a fork with its own app id changes it |
| `FF_ENROLL_TOKEN` | — | Optional bootstrap enroll code; more codes come from `admin codes` |
| `FF_ADMIN_EMAILS` | — | Comma list. Turns on the admin page for these addresses; off when empty. Sign in with an emailed code, or with an authenticator code once one is set up (Activity tab): either one, not both |
| `RESEND_API_KEY`, `FF_MAIL_FROM` | — | Email via Resend (preferred) |
| `FF_SMTP_HOST`, `FF_SMTP_PORT`, `FF_SMTP_USER`, `FF_SMTP_PASSWORD`, `FF_SMTP_TLS` | —, `587`, —, —, `starttls` | Email over plain SMTP instead (Gmail app password, Fastmail, your own server), used only when no Resend key is set. `FF_SMTP_TLS`: `starttls`, `ssl` (port 465) or `none` (a local relay only) |

Put it behind HTTPS (Caddy or nginx). Android blocks plain-HTTP connections from the app by
default, so the phone can only talk to an `https://` server.

## Endpoints

Device requests are signed (`action|deviceId|t|extra`, ECDSA P-256) and must be within 10 minutes
of server time.

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/version` | Protocol level, oldest app level served, server version (no auth) |
| POST | `/v1/enroll` | Register a device key → `deviceId`. Needs `X-FF-Enroll` when codes are on |
| POST | `/v1/invites` | Signed (extra: the email, or empty). New invite → code, QR link; with `email`, the address is emailed a link |
| POST | `/v1/invites/cancel` | Signed. Cancel an open invite |
| POST | `/v1/partners/list` | Signed. Partners with their devices (and whether each has the current key), open invites |
| POST | `/v1/partners/unlink` | Signed. Remove a partner — **they are emailed** if they have email; their app finds the link gone |
| POST | `/v1/seal` | Signed. The report key sealed to one partner device (ciphertext only) |
| POST | `/v1/heartbeat` | Signed liveness; sends the report key's id, gets owner notices, devices to seal, open invites, ask answers |
| POST | `/v1/alerts`, `/v1/reports`, `/v1/unlock/ask` | Signed alert lines, sealed report slices, ask a partner |
| POST | `/v1/batches` | Signed batch from older apps: checked for alerts in memory, never stored |
| POST | `/v1/join` | A partner's device uses an invite (`code` or `link`) with its public key → its report link |
| GET | `/c/{code}`, `/j/{secret}` | The invite pages (QR link, emailed link): join in this browser, or open the app |
| GET | `/p/{token}` | Partner's private report page |
| GET | `/p/{token}/data` | The sealed report slices (ciphertext) the page opens |
| GET | `/p/{token}/key?d=ID` | That device's sealed copy of the report key, once the owner's phone has sent it |
| GET | `/p/{token}/alerts?after=T&d=ID` | What partners have been told, plus `online` and this partner's alert choices; partners' apps poll it |
| POST | `/p/{token}/devices`, `/devices/{id}/remove`, `/prefs`, `/unlock/{id}`, `/leave` | Add or remove a device, alert choices, answer an ask, leave |

## Not done

- Flat files; backups are yours (copy the data directory or volume).
- Join rate-limiting is per client IP, in memory; behind a reverse proxy it needs the real IP.
- Report tokens are kept in the clear (so alert emails can link to the report).
