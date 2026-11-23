# Focus Fence accountability server (prototype)

**Status: small-group server, built and tested by CI** (`./gradlew -p server test` from the repo root).
Running for one person since 2026-11-23, after an independent security audit; not load-tested. Flat-file storage:
fine for a person or a few, not for many.

It compiles the phone's pure `app/src/main/java/app/focusfence/dns/` code too, so the server
alerts on exactly what the phone's `Digest.alertText` alerts on, and the partner web page shows
the same `Digest` the partner app does. One rule set for both ends.

## Two ways to be a partner, side by side

**1. Web + email (+ optional SMS) — the default.**
The phone enrolls a hardware-backed P-256 key, then uploads its activity log as signed,
hash-chained batches every 15 minutes and heartbeats. The owner creates a one-time invite code
in the app and sends it to their partner. The partner joins at `/join`, and gets:
- an email alert when protection changes (filter off, accessibility off, log cleared, records
  missing, partner removed, …) and when the phone goes silent;
- a weekly report email;
- a private report page at `/p/{token}` (the link is their login);
- optional SMS alerts: opt-in, verified with a code, alerts only, throttled to one per 10 min.

**2. App to app — the equal option.**
Both people run the app and pair with a key the owner sends over any message app. The key
never reaches the server: the phone derives a mailbox id, an access token and an encryption key
from it (`dns/Pairing`). The server is a **blind mailbox** — it stores ciphertext under an id it
can't link to anyone, and keeps only a hash of the token. The partner's app polls, decrypts, shows
the report, and raises its own notifications. It also shows a rotating unlock code that the
owner's phone accepts offline as the partner PIN.

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
client address behind a proxy on the Docker bridge. The compose file itself (and Caddy) has not been brought up
end to end yet: say so in any bug report.

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
| `FF_STALE_MINUTES` | `240` | Silence before "phone went silent". Long enough for overnight Doze. |
| `RESEND_API_KEY`, `FF_MAIL_FROM` | — | Email via Resend |
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
| POST | `/v1/enroll` | Register a device key → `deviceId` |
| POST | `/v1/invites` | Signed. New one-time invite code (24 h, max 5 live) |
| POST | `/v1/partners/list` | Signed. Partners (emails masked) |
| POST | `/v1/partners/unlink` | Signed. Remove a partner — **they are emailed/texted** |
| POST | `/v1/batches` | Signed batch: seq + hash chain + signature checked; 409 returns the server cursor |
| POST | `/v1/heartbeat` | Signed liveness |
| POST | `/v1/shots` | Signed encrypted screenshot (ciphertext only) |
| POST | `/v1/mailbox/{id}` | Create a blind mailbox with a token hash |
| POST/GET | `/v1/mailbox/{id}/msgs` | Post / read ciphertext (`X-FF-Token` header) |
| GET/POST | `/join` | Partner joins with an invite code |
| GET | `/p/{token}` | Partner's private report page |
| POST | `/p/{token}/sms`, `/sms/verify`, `/sms/stop`, `/leave` | Partner settings |

## Not done

- No deployment, backups, or admin tooling. Flat files.
- Join rate-limiting is per client IP, in memory; behind a reverse proxy it needs the real IP.
- Screenshots: the endpoint exists, but the phone doesn't upload them yet.
- Report tokens are kept in the clear (so alert emails can link to the report).
