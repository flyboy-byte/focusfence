# Focus Fence accountability server (prototype)

**Status: prototype. Not built by the app's CI** (which builds only `:app`), and not yet
wired to the phone — the app does not sign or upload batches yet. This is the receiving half of
the device→server→partner loop, here to prove the design in
[`../docs/specs/accountability-architecture.md`](../docs/specs/accountability-architecture.md).
Swap the flat-file store for Postgres + object storage before anything beyond personal use.

## What it does

- **Enroll** a device by its Ed25519 public key and the partner's email.
- **Ingest** signed batches of the *same* JSON-lines activity records the phone writes
  (`docs/specs/event-log.md`), checking three things: the Ed25519 signature, a per-device
  sequence with no silent gaps, and a hash chain so already-uploaded history can't be rewritten.
  A sequence gap is *recorded and reported*, not hidden — missing data must be visible.
- **Heartbeat** for liveness. A device that goes silent past `FF_STALE_MINUTES` triggers one
  partner alert — the only way to catch uninstall, factory reset, or a powered-off phone.
- **Email** the partner (via Resend) on integrity changes and staleness; a weekly report is the
  same `Report` summary rendered to HTML.
- **Screenshots** arrive as opaque ciphertext (`/v1/shots`). The server has no key and cannot
  read them; the partner decrypts in their own key (the device encrypts with `dns/Ecies`).

## Trust model

This is self-accountability: the phone's owner enrolls their *own* device and chooses the
partner. The server is deliberately unable to read screenshots, and it cannot turn capture on —
only the device does that, and the device shows that capture is running. The server's job is to
make the record it receives tamper-evident, not to control the phone.

## Run

```sh
cd server
RESEND_API_KEY=... FF_MAIL_FROM="you@yourdomain" ./gradlew run
# no RESEND_API_KEY → emails print to stdout, so it runs with no account
```

Env: `FF_DATA_DIR` (default `./data`), `FF_PORT` (8080), `FF_STALE_MINUTES` (90),
`RESEND_API_KEY`, `FF_MAIL_FROM`.

## Endpoints

| Method | Path | Body | Purpose |
|---|---|---|---|
| POST | `/v1/enroll` | `partnerEmail`, `publicKeySpki` | Register a device key → `deviceId` |
| POST | `/v1/batches` | signed batch | Ingest records (seq + hash + signature checked) |
| POST | `/v1/heartbeat` | signed `t`, `lastSeq` | Liveness |
| POST | `/v1/shots` | `shotId`, `ciphertextB64`, signature | Store an encrypted screenshot |
| GET | `/partner/{id}` | — | Minimal HTML report (put auth in front for real use) |

## Not done

- No auth on the partner view yet (prototype).
- The device-side client (sign + batch + upload, heartbeat scheduling, blob encryption to the
  partner key) is not built; `dns/Ecies` and `dns/Pin` are the crypto primitives it will use.
- Flat-file store; fine for one person, not for many.
