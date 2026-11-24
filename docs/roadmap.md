# Roadmap

_Written 2026-11-23 by @flyboy-byte (with Claude). This is the **active** plan for the accountability direction; older
orderings (`docs/approach.md` B1–B8, `docs/plans/00-roadmap.md`) are history. `STATE.md` says where things stand;
this says what comes next and in what order._

**Pace:** one phase at a time, at most two PRs open in review, a release only after the previous build has been tried on
a phone. Every item names its **exit**: the thing that has to be true, not the docs written about it. Tick items as
they land (`[x]`, with the PR number); strike through, never delete.

Legend: 🧪 tested in CI only · 📱 seen on a phone · 🔒 security-sensitive (needs an independent audit before release) ·
👤 needs a decision from a person · 🛠 needs repo admin (@sidewinderzz) · 🖥 needs the VPS `vps-sudo` window.

---

## Phase 0 — Land what's already open

The work is written; it needs merging, deploying and one real test.

- [ ] **Merge in order:** #15 (D-021 signing key), #16 (D-022 collaboration), #17 (retire `shareddev`), #19 (D-023 E2E
  partner reports). Decision numbers assume that order.
- [ ] 🛠 **Repo settings:** default branch → `main`; protect `main` (PR + `Android CI` required); auto-delete merged
  branches; then delete `claude/android-vpn-website-blocker-1otcbn`.
- [ ] 🛠 **Close #11** (old signing setup at 2.1.0, contradicts D-021) and delete its branch.
- [ ] 👤 **Confirm the keystore backup exists** (D-021's one open line). If not, make it first.
- [ ] 🖥 **Deploy the server from `main`** (D-023). The startup purge deletes the readable records from earlier versions.
- [ ] **Release 2.11.0** from `main`: version-bump PR, merge, tag `v2.11.0-accountable`.
- [ ] 📱 **Phone checks** on 2.11 (send the export after):
  - invite your own email as a partner, open the link in your normal browser, see the report decrypt;
  - reboot with the filter on: it comes back by itself (`autostart` record);
  - an app update sends no "filter off" email;
  - Setup cards, About row, partner code skipping the strict wait (needs the second phone);
  - `net` records + DNS race: was the morning slowness gone?
- [ ] 📱 **Second phone:** group create/join/approve both ways; each sees the other's report and alerts.

**Exit:** `main` is the default and protected, 2.11 is on your phone, and the checks above are 📱, or have issues filed.

---

## Phase 1 — Independent users and end-to-end privacy (D-023 follow-through)

- [ ] 🔒 **Independent audit of D-023 + the admin page** (the `extraction` session): report key handling, `report.js`,
  CSP scope, `/v1/reports` and `/v1/alerts`, admin login.
- [ ] **Key UX edge:** an invite opened in a chat app's built-in browser and a confirm link opened in Chrome leave the key
  in the wrong browser. Options: show the key link again on the confirm page ("open this in the browser you'll use"),
  or a short "pair this browser" code. Pick one after watching it happen once.
- [ ] **Key rotation:** a button to make a new report key (old partners need the new link). Removing a partner already
  cuts their server access; rotation covers a leaked link.
- [ ] **Onboard others:** a per-person code from `/admin` only for email partners; groups and app pairing need no code.
  Short "Using my server" note (what the operator can and can't see), linked from the privacy page.
- [ ] 🖥 **Capacity:** raise `FF_MAX_DEVICES` (now 4) when the third email-partner user arrives.
- [ ] **Docker Compose end to end** (never run yet), so anyone can host their own server; then decide 👤 whether to run
  `server-release.yml` (publishes a GHCR image under the upstream repo).

**Exit:** the audit's findings are fixed, someone other than you uses the server (email partner or group), and the
compose path has been run once.

---

## Phase 2 — Screenshots to partners 🔒 👤

Nothing is built until the level is chosen. **Fact that decides it:** of 8 flagged screenshots you reviewed, 7 were
"not a problem". Automatic sending today would mostly send innocent images of you.

| Level | Partner gets | Risk |
|---|---|---|
| A. Nothing (today) | score, label, app | none |
| B. Owner sends | you pick a flagged shot to send | weak accountability, little new surface |
| C. Blurred thumbnail | auto-sent for strong/repeated flags, heavily blurred | low: a blur can't be undone |
| ~~D. Full image on strong flags~~ | ~~the real image~~ | **Ruled out 2026-11-23** (see below) |

**Decided 2026-11-23 (@flyboy-byte): a partner never receives a clear image of explicit content.** "If it's hard porn
I don't want a clear image, period." So level D is out, and the ceiling is C: a blur strong enough that explicit
content is unrecognisable whatever the label or score, checked by eye on real flagged shots before release. Besides
the intent, this means neither the server nor a partner ever holds explicit imagery from someone's phone (including
anything that could involve a minor), and a false flag never sends an innocent private image either.

**Still open:** B or C (or both: blurred thumbnail sent automatically, the owner able to add context).

- [x] 👤 ~~Choose whether D is ever allowed~~: no, never a clear explicit image (2026-11-23).
- [ ] 👤 **Choose B, C or both.**
- [ ] **Measure first:** the false-alarm rate from `rev` answers over two weeks; retune `FlagPolicy` with the reason
  recorded in `docs/research/ai-detection.md`.
- [ ] **Build (whatever level is chosen), under these rules:** sealed with the report key (server never sees an image);
  off by default, per partner; you always see exactly what was sent; never in email; 7-day expiry, size cap; the
  pasted "partner key" box in Monitoring is replaced by this, not kept beside it.
- [ ] 🔒 **Audit before release.**

**Exit:** the chosen level is on a phone (📱), audited, the blur is verified unrecognisable on real flagged shots, and the privacy page says exactly what is sent (and that clear images never are).

---

## Phase 3 — A solid `main`

- [ ] **Fix #18:** a site rule on `google.com` silently kills push notifications (recommended: never block a short list
  of shared infrastructure hosts).
- [ ] **Block triage + notifications** (from the #13 discussion): sort every block into Silent / Notice / Partner;
  categories (adult, gambling, scam) can reach the block screen or notification; generic text for category blocks.
- [ ] **Rewrite `docs/HANDOFF.md` for `main`** (it still carries the old "don't touch main" rules).
- [ ] **Tidy docs:** mark `docs/approach.md` and `docs/plans/00-roadmap.md` as history pointing here; one `STATE.md`.
- [ ] **Server hardening backlog:** off-box backups, outbound firewall for the service, fail2ban on nginx logs 🖥.

**Exit:** no known bug that silently breaks the phone, and a cold session can start from `main` docs alone.

---

## Phase 4 — Design and README

- [ ] **Design pass, screen by screen**, from your screenshots: Settings (≈26 controls → grouped cards: Filtering,
  Accountability, Monitoring, Privacy, Advanced), Home, Monitoring, Group, partner report in the app, the block
  screen. One PR per screen; each shows before/after screenshots.
- [ ] **"Recent flags"** and **report exclusions** (sites you never want in a report), if still wanted after the pass.
- [ ] **README** with the `readme` skill: what it is in one screen, the optional layers, install, run-your-own-server,
  honest limits, links to privacy and decisions.

**Exit:** you'd show the app and the README to someone without apologising for either.

---

## Phase 5 — Collaboration that explains itself (people and agents)

@sidewinderzz said @flyboy-byte can take the lead here. The aim is mild: every PR should make it easy for the other
maintainer to understand and merge it.

- [ ] **PR contract in `AGENTS.md` / `CLAUDE.md`** (read by both sides' Claude sessions): every PR states **what and
  why**, **pros**, **cons and risks**, **what the other maintainer might object to, and why it's still worth it**,
  **tested** (📱 / 🧪 / not), and **how to revert**.
- [ ] **`.github/pull_request_template.md`** with those headings pre-filled.
- [ ] **A small CI check** that fails a PR whose description is missing a heading (the actual enforcement; it only asks
  for a sentence under each).
- [ ] **Squash-merge only** (🛠 repo setting), so one PR = one commit on `main` and undoing a feature is one revert.

*Pulled forward:* the template and the `AGENTS.md` rule are tiny; they can land in Phase 0 so every later PR uses them.

**Exit:** the next three PRs from each side carry the sections without being reminded.

---

## Phase 6 — A changelog you can revert from

- [ ] **Labelled PR titles** (squash commits inherit them): `feat(scope):`, `fix(scope):`, `docs:`, `refactor(scope):`,
  `perf(scope):`, `chore:`; scopes such as `dns`, `sync`, `server`, `ui`, `monitor`, `group`.
- [ ] **`CHANGELOG.md`** generated at each release from those commits (a small script or `git-cliff`, run by
  `release.yml`), grouped by type, each line linking its PR, with a "how to revert" note per release.
- [ ] **Backfill** from the existing `v*-accountable` tags so it starts complete.

**Exit:** the 2.12 release notes write themselves, and reverting any listed change is one command.

---

## Backlog (parked, with the reason)

| Item | Why parked |
|---|---|
| Device attestation at enroll | enrollment codes + caps are enough at this size |
| SMS alerts in production | needs a provider and a budget 👤 |
| GitHub Pages for the site | private repo; the server serves `/about/` meanwhile |
| Admin landing page (plan 06 phase 4) | not needed until others onboard themselves |
| "Your partner is trying to unlock X" message | extra server round trip; revisit after Phase 2 |
| A "test my speed" button | uses data; the `net` records may answer the question first |
| Weekly report / silence alert / SMS seen on a phone | will happen naturally in Phase 0–1; tick in `STATE.md` |
