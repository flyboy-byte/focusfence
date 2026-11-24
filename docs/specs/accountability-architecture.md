# Accountability architecture (proposed)

**Status: proposal.** Nothing here is implemented except where marked *(built)*. It turns the
research handoff into concrete component specs for the phases in
[`../plans/02-ever-accountable-parity.md`](../plans/02-ever-accountable-parity.md). Every choice
marked **Decision** is open, and the measurements in
[`../plans/03-measurement-plan.md`](../plans/03-measurement-plan.md) should inform it.

## Principles

1. **Detection is the product; enforcement is optional.** Every observation becomes a record.
   Whether anything is blocked is a separate policy decision.
2. **Observation, classification, policy, enforcement and reporting stay separate.** Services
   are adapters that observe and enforce. Decisions live in pure `dns/` code with tests.
3. **A protection gap must never be silent.** Locally, that means the integrity trail
   *(built)*. Remotely, it means a heartbeat, because a removed or dead phone can't report
   itself.
4. **Measure before widening.** Any new sensor ships with diag counters for its cost and miss
   rate.
5. **Lightweight default, strong mode opt-in.** Device Owner never becomes a requirement.

## Component map

```
 observers (Android adapters)                 pure core (dns/, JVM-tested)
 ───────────────────────────                  ────────────────────────────
 DnsVpnService      ── query ──────────┐      Blocklist / BlockRule     policy
 UrlFilterService   ── url, text ──────┤      Categories (new)          classification
 UsageCollector     ── app fg/bg ──────┼────▶ LogEvents                 record schema   (built)
 Monitor            ── integrity, bat ─┤      AppTimeline, Battery*     derivations     (built)
 ScreenCapture (new)── image score ────┘      ReportBuilder (new)       summaries
                                   │
                                   ▼
                     EventLog (JSONL, built) ──▶ EventStore (Room, new) ──▶ SyncWorker (new)
                                                                              │ signed batches
                                                                              ▼
                                                    backend: ingest · heartbeat · reports · alerts
```

## 1. Local event store

*(built)* `EventLog`: append-only JSONL, two channels, rotation. This is enough for measurement
and local review.

**Next:** keep the JSONL as the capture format, and add a Room table for records that will sync.
Columns:
- `seq`
- `t`
- `type`
- `payload` (the JSON line)
- `uploaded_at` (nullable)

This adds an upload cursor without a second schema. Activity records only; diag never syncs by
default.

**Decision:** retention after confirmed upload: keep N days locally, or delete on acknowledgement.

## 2. Classification (domain categories)

- A `ListSource` model with these fields:
  - id
  - url
  - license
  - category
  - updated
  - count
  - enabled
- Imported lists produce `host → categories` labels. A `dns` record gains `cat` (comma list)
  whenever the host matches, **whether or not it blocks**.
- Policy is per category: `report` (default), `report+block`, or `ignore`. An explicit user rule
  always wins over category policy. The allowlist overrides list-sourced blocks only (the
  conservative semantics from `00-roadmap.md`).
- The matcher stays as today's hash sets until list size forces otherwise (handoff F5).

**Decision:** which lists. HaGeZi NSFW/gambling are the obvious candidates, but their license
must be compatible with the repo license once that is chosen.

## 3. In-app content capture

- A per-app adapter interface: `packages`, `eventTypes`, and `extract(root): List<Snippet>`, with
  bounded node walks. Adapters register with the accessibility service only for their packages,
  which is the same scoping rule as today.
- Snippets are short visible-text extracts, deduplicated per screen, and logged as a `text`
  record with `pkg` and `text` (capped length).
- Every adapter reports `a11y_ev`, `extract_ms`, `extract_miss` to diag.

**Decision:** which apps get adapters first. Use the activity log's top apps by foreground time
to choose.

## 4. Screenshots and classifier

- Capture: `AccessibilityService.takeScreenshot` (API 30+) first. MediaProjection only if that
  proves insufficient. Sampling stays sparse and randomized while a watched app is in the
  foreground.
- Inference runs on-device (TFLite), with a downscaled copy of the frame and the original
  discarded immediately. Output is a `shot` record holding a score vector and the model version.
- Retention mode **Decision**:
  - S1 score only
  - S2 flagged frames only (recommended default)
  - S3 blurred low-res
  - S4 all frames
- Images, when retained, are encrypted to the partner's key before upload (S5/P3 below).
- Diag: `shots`, `infer_ms`, `secure_blank` (FLAG_SECURE frames), and the battery delta.

## 5. Integrity and tamper

*(built)* Local trail: these are all logged:
- `integ` changes for VPN, always-on, accessibility enabled/bound, Usage access, Private DNS
- process starts, reboots, app updates
- log setting changes and clears
- strict-mode refusals

**Remote (new):**
- **Sequence continuity:** the server checks per-device `seq` for gaps. A gap is shown to the
  partner as "N records missing".
- **Hash chain:** each uploaded batch carries `prev_batch_hash`. A rewritten or reordered history
  fails verification.
- **Device key:** an Android Keystore keypair generated at enrollment. Batches are signed, and
  the server stores the public key.
- **Heartbeat:** a tiny signed ping on a periodic WorkManager job (≥ 15 min, battery-friendly),
  plus every batch. The server marks the device stale after a configurable interval. Stale is a
  partner alert, and it is the only detector for uninstall, factory reset or prolonged power-off.

**Decision:** stale threshold. EA-style weekly reports tolerate hours, while alerts want much
less.

## 6. Backend

Small and self-hostable. One process, PostgreSQL, filesystem/S3 for images. *A Kotlin/Ktor prototype exists under [`../../server/`](../../server) — flat-file store, Ed25519-signed sequence-checked ingest, heartbeat/staleness, Resend email, ciphertext-only screenshots. Not yet wired to the phone.*

| Endpoint | Purpose |
|---|---|
| `POST /v1/enroll` | Enrollment code + device public key → device id |
| `POST /v1/invites` | Device creates a partner invite → one-time code (see *Partner linking*) |
| `GET/POST /join` | Partner redeems the code on the web |
| `POST/GET /v1/mailbox/{id}[/msgs]` | Blind mailbox for app-to-app pairing |
| `POST /v1/batches` | Signed batch: `{device, first_seq, last_seq, prev_hash, records[]}` → ack `{last_seq}` |
| `POST /v1/heartbeat` | Signed `{device, t, last_seq, integrity_summary}` |
| `GET  /v1/policy` | Pull-based policy/config for the device (WorkManager) |
| Partner web | Timeline, alerts, weekly report settings |

- Device→server: batch and upload on WorkManager with network constraints, plus an immediate
  upload for integrity records.
- Server→device: periodic pull first. FCM or UnifiedPush only if real-time control is needed.
- Reports: a scheduled weekly email (EA parity) and immediate alerts for integrity events and
  staleness. A daily digest is optional.
- An optional LLM summary runs over *aggregated counts only* (handoff §15), never per-event or
  per-image.

### Partner linking — two equal options (implemented, 🧪)

**Email / web (default).** The owner taps *Invite a partner*; the phone (enrolled with a
hardware-backed P-256 key) gets a one-time code from the server — 8 chars, 24 h, single use,
max 5 live, join attempts rate-limited — and shares a join link. The partner enters the code,
name and email at `/join` (and optionally a phone number for texts), and gets a private report
page `/p/{token}`, alert emails, and a weekly report.

**App to app (pairing key).** If both people use the app, the owner taps *Pair with a partner's
app*. The phone makes a random 128-bit key and shows it as a 27-character code with a check
character (`dns/Pairing`: typos and swapped neighbours are rejected at entry). The owner sends it,
with the server address, over any message app; the partner types it into *I'm a partner for*.
From the key both apps derive, independently:
- a mailbox id (where they meet on the server),
- an access token (the server stores only its SHA-256, so a server breach can't post),
- an AES-256-GCM key (the server relays ciphertext it can't read), and
- a seed for rotating 8-digit unlock codes (10-min steps, ±1 for skew). The partner's app shows
  the current code; the owner's phone accepts it offline wherever a partner PIN is required.

The owner's phone posts instant alerts and a digest every 6 h; the partner's app polls every
15 min, notifies on alerts, and flags 14 h of silence.

**Removing a partner is never silent.** Both paths go through the `REMOVE_PARTNER` friction guard,
log a `svc partner` record (which other partners are alerted about), and tell the removed partner:
the server emails/texts an email partner; the phone posts a final message to a paired partner
before forgetting the key (and their app flags the silence if that post fails).

### Notification channels

- **Email** (Resend): weekly report, alerts, invite confirmation.
- **SMS** (Twilio or Telnyx behind one `Notifier` interface): alerts only, opt-in on the join form
  or report page, confirmed with a 6-digit code (carrier consent), throttled to one per partner
  per 10 min. Weekly reports stay email.
- **Partner app**: its own notifications, from decrypted mailbox messages.

All three alert on the same rule, `Digest.alertText`, compiled into the server from the phone's
`dns/` sources.

**Decision:** backend language. Kotlin/Ktor would let the `dns/` schema code be shared, which is
a real advantage. Go is the smallest to operate. Python is the fastest to write. The deciding
factor is maintainability by whoever runs it.

**Privacy tiers (decision):**
- P1 server-readable records
- P2 + encryption at rest
- P3 + E2EE images (recommended)
- P5 hybrid with E2EE text snippets

## 7. Settings lock (StrongHold parity)

Extend `StrictLock` with an unlock *authority*:
- `timer` (today)
- `own_pin` (a local PIN plus the timer)
- `partner_pin`: a code delivered to the partner via the backend, or printed for offline use

Every unlock request, grant and loosening action becomes an activity record.

## 8. Managed mode (Device Owner)

> **Removed 2026-11-24 (D-028).** Only uninstall protection (device admin) remains.

- A `DeviceAdminReceiver`, provisioned via `adb shell dpm set-device-owner` (development) or QR
  code. Shizuku-assisted provisioning is an option (Déchaîner's pattern).
- Policies (as built — see [`device-owner.md`](device-owner.md)):
  - uninstall is **not** blocked; it's detected (integrity trail + heartbeat) and reported
  - always-on VPN **without lockdown** — lockdown drops all non-DNS traffic with our DNS-only
    routes (it caused a real no-internet bug)
  - `DISALLOW_CONFIG_VPN`, Private DNS restriction
  - `setPackagesSuspended` for blocked apps
  - unknown-sources restriction
  - network logging (`DnsEvent`/`ConnectEvent`, package-attributed)
- **Recovery is mandatory before enabling:** a long random recovery code shown once (to be
  printed), partner-held release, and a documented factory-reset fallback.
- Network logging feeds the same `dns` record type with a new `pkg` field. That answers the
  current "which app made this lookup" gap.

## 9. Distribution

- Play: `targetSdk 36`, prominent disclosure and accessibility declaration, Usage-access
  rationale. Device Owner provisioning is not available via Play install.
- Sideload/F-Droid-style build: everything, including managed mode.

**Decision:** whether two flavors are worth it, or sideload only at first.

## Open decisions, in the order they block work

1. Repo license (blocks importing any list or code).
2. Same repo vs fork (maintainer).
3. Category lists and their licenses (Phase 1).
4. Backend language and hosting (Phase 3).
5. Screenshot retention mode and privacy tier (Phase 4).
6. Stale threshold and alert policy (Phase 3).
7. Two builds or one (before any Play release).
