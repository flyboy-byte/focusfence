# Backend deploy

**Status 2026-11-23: live.** The reference server runs at `https://ff.flyboybyte.com` behind nginx with a Let's Encrypt
certificate, as a sandboxed systemd service. The operator's box-specific runbook (host, neighbouring services, ports,
DNS provider) is kept privately on purpose (public-release audit, 2026-11-24); this file is the generic shape, for
anyone running their own server. `server/README.md` has the configuration reference.

## Layout that works

| Piece | Choice | Why |
|---|---|---|
| User | a dedicated system user (`ffbackend`) | nothing else can read the data dir |
| Code | `/opt/ffbackend` (`installDist` output), owned by a deploy user, group `ffbackend`, 750 | the service can read, not write, its own code |
| Data | `/var/lib/ffbackend/data` (700) | flat files: devices, sealed reports (D-023), mailboxes |
| Secrets | `/etc/ffbackend/env` (root:ffbackend 640) | email provider key, enrollment code, `FF_ADMIN_EMAILS` |
| Service | systemd unit: `ProtectSystem=strict`, `ReadWritePaths=` the data dir, `NoNewPrivileges`, empty `CapabilityBoundingSet`, `MemoryMax`, `UMask=0077` | a bug in the server can't reach the rest of the box |
| Proxy | nginx site proxying `127.0.0.1:$FF_PORT`; `access_log off` (invite and report tokens are in URLs); HSTS | TLS and one public port |
| Bind | `FF_HOST=127.0.0.1`, a free `FF_PORT` | only nginx can reach it |
| Admin | `server/deploy/ff-admin.sh` runs CLI commands as the service user with the unit's env (audit S1) | |

## Deploying a new build

1. `./gradlew -p server installDist`, copy `server/build/install/focusfence-server/` to the box.
2. Keep the running build (`cp -a /opt/ffbackend /opt/ffbackend.prev`), copy the new one in, fix ownership, restart.
3. Check `/healthz` for up to a minute; if it isn't `ok`, copy `.prev` back and restart. Note the change in your own
   ops log.

## Rules worth keeping

- Arm a rollback before anything goes live; change only this service's files, unit and nginx site.
- The first start after D-023 deletes readable activity records left by older versions (by design).
- Rate limits trust `X-Forwarded-For` only from `FF_TRUSTED_PROXIES` (loopback by default).
