# Backend deploy: scope for the next session

_Scoped 2026-11-22 from the laptop (has VPS access). Nothing here has been run; every box fact below was
read-only on 2026-11-22. This is a plan, not state: `STATE.md` wins if they disagree._

**Status 2026-11-23: steps 1 to 6 DONE and live** (service `ffbackend` enabled, `https://ff.flyboybyte.com` with a
Let's Encrypt cert to 2027-02-21, Resend sending-only key in `/etc/ffbackend/env`). Verified from the laptop only:
`/join` 200 over valid TLS, unsigned `/v1/batches` 400, 2 MB batch 413, HSTS/nosniff/no-referrer present, other
sites unchanged. **Gate A (Pixel enroll, first batch, partner email) has not run.** Rollbacks:
`/usr/local/sbin/ffbackend-nginx-rollback.sh` then `/usr/local/sbin/ffbackend-rollback.sh`. Change log on the box:
`~/security/hardening-log.md`. Also added: `FF_HOST` (bind address) in `Main.kt`.
**Audit (extraction session, 2026-11-23): 11 findings, server-side ones fixed and redeployed the same day** (quotas, double opt-in, escaping + CSP, streaming reads, seq-tied records, unit hardening 4.3 to 1.5). Report: `~/projects/extraction/docs/ffbackend-security-audit-2026-11-23.md`.

**Goal:** `server/` running on the `flyboybyte` VPS behind HTTPS, so the phone's partner loop can run for
real for the first time. Gate: **one phone enrolls, uploads a batch, and a partner email + report page
works.** Two-phone app-to-app pairing is the second gate.

## Decisions to make first (only Logan can)

| # | Question | Recommendation |
|---|---|---|
| 1 | **Host** | `flyboybyte` (51.81.80.126). `milk` is customer data and read-only: not used. |
| 2 | **Reuse the dns-filter or separate?** | **Separate service, same pattern.** dns-filter is DNS-over-TLS on :853; the backend is an HTTPS JSON API. The app's "your own DNS server" option only sends DNS queries, so it can't carry enroll/batches/mailbox. What we do reuse is the *framework*: a dedicated user, `/opt` + `/var/lib` + `/etc/<app>/env`, a sandboxed system unit, an nginx site proxying a localhost port (as `dnsjoin` does), certbot. Removing dns-filter's pieces buys nothing. |
| 3 | **Domain** | `ff.flyboybyte.com` (no record exists yet; no wildcard, so it needs an A record to 51.81.80.126). The `*.dns.flyboybyte.com` wildcard cert does not cover it. Use a plain HTTP-01 cert via nginx, which is simpler than the Spaceship DNS-01 hook. Logan adds the A record (or says I may use the Spaceship API key, which is root-only on the box). |
| 4 | **Email** | **Sender: `reports@flyboybyte.com`** (Logan, 2026-11-23: "Reports works"; not yet proven from here, the first real email is the test). Resend key: Logan supplied a full-access one; a sending-only key for this server is recommended. It goes only in `/etc/ffbackend/env`, never in a repo file. No key means emails print to the journal. |
| 5 | **SMS** | Skip for day one (`FF_SMS_PROVIDER=none`). Decide provider and budget later. |
| 6 | **Privacy tier** | STATE recommends P3 (server-readable records, end-to-end-encrypted screenshots). Confirm before real data flows. |

## Box facts (read-only, 2026-11-22)

- 4 vCPU, 7.7 GB RAM (about 5.7 GB available), 47 GB free disk.
- **Java is not installed.** The server is Kotlin/Ktor, JVM 17 (`jvmToolchain(17)`).
- Ports in use on 127.0.0.1: 5757, 5758, 5760, 5761, 5770, 8080 (moomoo dashboard), 8787, 8853, 11111, 22222.
  **The server's default `FF_PORT=8080` collides with the moomoo dashboard: set `FF_PORT=5762`.**
- ufw already allows 80/443; no firewall change is needed. nginx has a site per app in `sites-enabled/`.
- `ubuntu` sudo needs a password: Logan runs `vps-sudo` (about 20 min window) before any root step.
- Rules from the VPS architecture doc: never touch `sshd_config`, `authorized_keys`, `ufw` or `sudoers`;
  **adding an nginx site counts as touching nginx, so it needs Logan's explicit OK** at that step.

## One code fix before deploy

`server/.../Main.kt:305` rate-limits joins with `call.request.origin.remoteHost`. Behind nginx that is
always `127.0.0.1`, so every partner shares one limit bucket (one burst locks everyone out) and the per-IP
protection is gone. Fix: read `X-Forwarded-For` (nginx sets it from `$remote_addr`, as the `dnsjoin` site does)
**only** when the peer is loopback, add a test, bump nothing on the phone. Do this on the branch first.

## The plan (in order, each step reversible until the last)

1. **Code (laptop, no VPS): DONE 2026-11-23, uncommitted.** Rate-limit fix (`clientKey` in `Main.kt`) + 3 tests
   (`ClientKeyTest`); 12 of 12 server tests green. Built `build/install/focusfence-server/` (14 MB, `bin/` +
   `lib/`; classes are Java 17 bytecode, major 61). No Gradle on the box, so that folder is what gets shipped.
   **Laptop gotcha:** the system JDK is 27, which Gradle 8.9 can't parse (`27.0.1.0.3`). Run with
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -p server test`, and not with `--offline` (nothing cached).
2. **Prep on the box (needs a sudo window):** `apt install openjdk-17-jre-headless`; create user `ffbackend`
   (`--system --no-create-home --shell /usr/sbin/nologin`, passwd home `/var/lib/ffbackend`); dirs
   `/opt/ffbackend` (ubuntu:ffbackend 750), `/var/lib/ffbackend` (700), `/etc/ffbackend/env` (root:ffbackend 640).
3. **Env file:** `FF_PUBLIC_URL=https://ff.flyboybyte.com`, `FF_PORT=5762`, `FF_DATA_DIR=/var/lib/ffbackend/data`,
   `FF_STALE_MINUTES=240`, Resend key and sender if available. Written with `sed`/`tee`, never printed.
4. **Unit** `ffbackend.service`: `User=ffbackend`, bind 127.0.0.1 only, `NoNewPrivileges`, `ProtectSystem=strict`,
   `ProtectHome`, `ReadWritePaths=/var/lib/ffbackend`, `PrivateTmp/Devices`, empty `CapabilityBoundingSet`,
   `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, `UMask=0077`, a JVM heap cap (`-Xmx256m`).
   Check with `systemd-analyze security`. Plus a rollback script, as every other app on the box has.
5. **Start it on localhost only** and curl it: `/join` serves, an unsigned `/v1/batches` is rejected,
   `journalctl` clean. Nothing public yet.
6. **DNS + HTTPS (needs OK for nginx):** A record `ff.flyboybyte.com`; an nginx site modelled on
   `dns.flyboybyte.com` (own `limit_req` zone, small body cap on the JSON routes, larger only on `/v1/shots`,
   `access_log off` because invite and report tokens sit in URLs, HSTS); certbot HTTP-01; `nginx -t` first.
7. **Gate A, on the Pixel 9:** set the server address in the app, enroll, wait for a batch, check
   the data dir has it (names only, not contents), then create an invite and join as a partner with a real
   email. Expect first-contact bugs (STATE says so); log each one.
8. **Gate B, second phone:** app-to-app pairing through the blind mailbox, plus the rotating partner PIN.
9. **Close out:** update `STATE.md` tags (🧪 to 📱 only for what actually ran), and note the deploy procedure.
   On the box, append a dated `## YYYY-MM-DD <what>` entry to `~/security/hardening-log.md` (what changed,
   users/paths/ports, rollback command). The extraction session already lists `ffbackend` / 5762 /
   `ff.flyboybyte.com` as PLANNED in its `flyboybyte-vps-architecture.md`; tell it when each part goes live.
   List the changed project files at the end of the session.

**Conventions from the extraction session (2026-11-22):** only inside a Logan-opened `vps-sudo` window;
nginx/ufw/sshd/sudoers only with his explicit OK; **arm a dead-man rollback timer (15 min, `systemd-run
--on-active`) before anything goes live**; never run `chown -R root:root` outside `/opt/libre-bridge`.

## Not in scope tomorrow

SMS; screenshot upload from the phone (endpoint exists, phone doesn't send); a database (flat files are fine
for a few people); off-box backups (a known gap on this box; at minimum add the data dir to whatever backs
up the others); the per-person DNS profiles from the dns-filter scope.

## Risks to watch

- JVM memory next to moomoo and the other apps: cap the heap and watch `systemctl show` after a day.
- Report tokens are stored in the clear (so alert emails can link to the report) and the data dir holds the
  activity records, so `/var/lib/ffbackend` stays 700 and out of any backup that leaves the box unencrypted.
- A bad enroll or a lost device key has no recovery path yet; test on the Pixel only after the data dir
  is backed up.
- Heartbeat silence is 4 h by guess: expect noise from overnight Doze on the first night.
