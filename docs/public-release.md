# Going public: audit and checklist

_Audit run 2026-11-24 by Claude for @flyboy-byte, before switching `sidewinderzz/focusfence` from private to public.
Code and docs were changed in PRs #40, #41 and this one; repo settings and the history decision are @sidewinderzz's.
Box-specific details are deliberately not in this file._

## Findings

| Front | Result | Action |
|---|---|---|
| **Credentials in any commit** (all 217 commits, every file version: private keys, GitHub/AWS/Google/Slack/Resend/Twilio tokens, JWTs, password assignments, long base64) | ✅ none | — |
| **Signing material** (`.jks`, `.keystore`, `.p12`, `.env` ever committed) | ✅ none; `keystore.properties.example` and `.env.example` hold placeholders only | — |
| **Server internals in docs** (host address, neighbouring services, internal ports, DNS provider, root rules) | ❌ in `docs/plans/04-backend-deploy.md` and older HANDOFF versions | Current files fixed (#40): generic self-host doc; box runbook kept privately. **History still has it** (see below). |
| **Personal email in commit metadata** | ❌ 22 early commits carry a personal address as author | Rewrite to the GitHub noreply address (see below). Later commits already use noreply. |
| **People's names** | ⚠️ a first name in docs alongside handles | Current files use handles (#40); history rewrite covers older ones. |
| **Phone log content** (browsing, messages, captured text) | ✅ never committed; exports are analysed locally only | — |
| **Workflows for a public repo** | ✅ PR builds get no secrets (`pull_request`, no `pull_request_target`); signing secrets only on tag pushes | Optional: require approval for first-time contributors' workflow runs (default for public repos). |
| **Past workflow runs and artifacts** | ⚠️ become public too. Logs show masked secrets only. One artifact from the one-off keystore recovery is encrypted to an offline key and **expires 2026-11-25** | Don't switch before it has expired. |
| **Pull request refs** | ⚠️ GitHub keeps every PR's commits (`refs/pull/N/head`) for good; a force-push can't remove them | This is why the history fix means a **new repo**, not an in-place rewrite. |
| **Licences** | ✅ MIT (`LICENSE`); the bundled model's licence in `app/src/main/assets/classifier/MODEL_LICENSE.txt` | — |
| **Large files** | ✅ one 12 MB model; repo pack ~12 MB | — |
| **Hard-coded repo URLs in the app** | ✅ none, so a repo move breaks nothing on phones | — |
| **README and About** | ❌ README described only the original blocker; About and topics empty | README rewritten (#41); About text below. |
| **Security contact** | ❌ none | `SECURITY.md` added (this PR). |

## The history decision (yours, @sidewinderzz)

The current files are clean, but older commits still contain the server details and the personal address. Options:

1. **New public repo from a scrubbed history (recommended).** A verified dry run exists: every commit on `main` and
   all `v*` tags, with the old deploy plan replaced by the generic one, names → handles, the personal address → noreply.
   After it: 152 commits, 23 tags, zero hits on every scan. This repo stays private as the archive (its PR refs and
   workflow history can't be scrubbed anyway). Costs: new repo URL; issues/PRs and release pages don't carry over
   (re-upload the latest APK); commit hashes change, so `CHANGELOG.md` is regenerated once in the new repo.
2. **One squashed commit** in a new repo: simplest and cleanest, loses all history. Only if (1) is unwanted.
3. **Flip this repo as-is:** not recommended; the server details and the personal address go public with it.

Bonus: public repos get GitHub Actions free on standard runners, which also ends the budget block on releases.

## Checklist

**@flyboy-byte (prep, no settings needed)**
- [x] Current files cleaned (#40), README (#41), `SECURITY.md`, this audit.
- [ ] Say which history option. Then Claude rebuilds the scrubbed history fresh from `main` and re-runs the scans.
- [ ] Set your local git email to the noreply address so it can't recur:
      `git config --global user.email 96597232+flyboy-byte@users.noreply.github.com`

**@sidewinderzz (repo settings)**
- [ ] Wait until the recovery artifact has expired (after 2026-11-25).
- [ ] For option 1: create the new repo, push the scrubbed history and tags, copy the Actions secrets
      (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), set it public, archive this one.
      For option 3: just Settings → General → Change visibility.
- [ ] Default branch → `main`. Delete the old default branch and the 2026-11-13 agent branch (`ccr-…`) once nothing
      needs them.
- [ ] Settings → General: allow squash merging only; automatically delete head branches.
- [ ] Settings → Security: enable private vulnerability reporting and secret scanning (free for public repos).
- [ ] About: description "Self-accountability for Android: a DNS-only filter, an honest on-phone record, and optional
      partners who get alerts and an end-to-end encrypted report." Topics: `android`, `kotlin`, `vpnservice`,
      `dns-filter`, `accountability`, `privacy`, `end-to-end-encryption`, `self-hosted`.
- [ ] Re-upload the latest release APK to the new repo's Releases (same signing key, same certificate).
