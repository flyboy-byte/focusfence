# Going public: audit and checklist

_Audit run 2026-11-24 by Claude for @flyboy-byte, before switching the repo from private to public. Code and docs were
changed in PRs #40–#45. @sidewinderzz transferred the repo to @flyboy-byte the same day (now `flyboy-byte/focusfence`).
**Outcome (D-027): made public as is**, history unchanged._
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

## The history decision

Decided 2026-11-24 by @flyboy-byte (D-027): **flip as is.** The current files are clean; old commits still carry the
server details and an early personal author address, all rated low. A scrubbed history was built and verified
(`~/projects/focusfence-public`, local only) but not used: rewriting every commit hash, the changelog links and every
clone, plus a GitHub Support purge of PR refs, was too much cost to hide things that are low risk and mostly public
anyway. Considered and not chosen: a new repo from the scrubbed history, or a force-push in place plus the purge.

## Checklist

**@flyboy-byte**
- [x] Current files cleaned (#40), README (#41), `SECURITY.md`, this audit.
- [x] History option: flip as is (D-027).
- [ ] Set your local git email to the noreply address so it can't recur:
      `git config --global user.email 96597232+flyboy-byte@users.noreply.github.com`

**Repo settings (@flyboy-byte, admin since the transfer)**
- [x] The keystore-recovery artifact deleted on 2026-11-24 (it would have expired 2026-11-25).
- [x] Default branch → `main`; squash merging only; automatically delete head branches (2026-11-24).
- [x] About description and topics (2026-11-24): "Self-accountability for Android: a DNS-only filter, an honest
      on-phone record, and optional partners who get alerts and an end-to-end encrypted report."
- [ ] Delete the old default branch and the 2026-11-13 agent branch (`ccr-…`).
- [ ] Settings → Change visibility → public.
- [ ] After it's public: Settings → Security: private vulnerability reporting and secret scanning (free for public repos).
