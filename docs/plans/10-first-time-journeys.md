# Plan 10: first-time journeys (started 2026-11-25)

**Why:** 2.18 to 2.24 shipped a lot in one day and little of it has been seen on a phone. Logan: "bug hunt, or try and
keep dialing in 1st time user experience, 1st time server setup, independent app users on a server enrollment, and
pairing of phones in partners and config". The order below was agreed with him on 2026-11-25.

**How each section runs:** trace the journey through the code (and the live server where it applies) as a brand-new
person would meet it, step by step. Note what breaks, what confuses, and what's missing. Ask Logan the questions only
he can answer (he answers from work, so they're batched). Fix in small PRs, ranked by how early a new person hits
them. No new features unless a journey needs one. A section is done when its exit condition holds, not when its notes
are written.

| # | Section | Exit condition | Status |
|---|---|---|---|
| 0 | Bug hunt of 2.21–2.24 | Every finding fixed or recorded here with a reason; tests green | done 2026-11-25 |
| 1 | Fresh install, no server | A new person gets from install to "protected" without leaving the app or guessing; Logan confirms on the second phone after a clean install | built, waiting for the clean-install check |
| 2 | First-time server setup (self-hoster) | From a fresh VPS to their own phone enrolled, following only `server/README.md` and the admin page | built; server-v1.1.0 to tag after merge |
| 3 | Joining someone else's server | From a pasted People message or enrollment code to "connected", with a clear answer when it fails | — |
| 4 | Pairing partners | Invite → partner joins (email and app) → first report and first alert seen by the partner; second-phone case included | — |
| 5 | Config after setup | Strict mode, screen checks, block lists and groups: defaults make sense together and nothing contradicts | — |

## 0. Bug hunt (2.21–2.24)

Found and fixed:
- **Suggestive images flooded the report.** Each changed capture of the same lingerie page logged its own "Suggestive
  image" line, so a few minutes of scrolling filled the timeline. Now one line per app per 10 minutes
  (`ScreenCapture.SUGGESTIVE_EVERY_MS`).

Checked and fine (so they aren't re-checked): installer state across leaving Home and a cancelled Android confirm
(it comes back as Failed with Try again); the three-way list buttons when the strict-mode guard is cancelled (the
choice reverts); core lists stored as Off count as Report everywhere (one read path, `CategoryStore.policy`); the
open report slice replaced, not piled up; a note can't be lost mid-upload.

Recorded, not changed: automatic sends are uncapped by Logan's choice (D-037), so scrolling through explicit images
sends one blurred thumbnail per changed capture.

## 1. Fresh install, no server

What a new person met (traced 2026-11-25): seven pages of reading, mostly time limits and section rules, never saying
the app is for porn accountability; stale lines (public DNS resolver, "Blocklist tab", "Block ads & trackers on
Settings", presets "on the Blocking tab"); the notification prompt popping over the first page; then an 8-card
checklist plus the whole guide again, whose first card sent them back to Home to find the switch; and on a fresh
install adult sites were only reported, with screen checks off.

Logan's answers: first user is mostly someone holding themselves accountable, but it must fit both; adult **Block** by
default; 3 or 4 short steps; offer screen checks as a step.

Built: `OnboardingActivity` is four steps: what it's for → Turn on (VPN prompt, then the notification permission) →
screen checks (screenshots + AI, then the accessibility permission, cost shown) → "Add a partner" or "Just me for now",
ending on Home or Partners. Adult defaults to Block (also for anyone who never changed it). The long guide stays under
Help with its stale lines fixed. Still to check on a phone: a clean install on the second phone, start to finish.

## 2. First-time server setup (self-hoster)

Traced 2026-11-25: the newest server release (1.0.1) predates D-035, so it couldn't serve the current app; the env
template still said 240-minute silence and never mentioned `FF_ADMIN_EMAILS`; the installer ended at "healthz ok" with
no way onto the server from the phone; the CLI sign-in link said an authenticator was still asked (one factor since
D-035); email needed Resend. Logan (D-040): self-host or be invited, no public server; Resend preferred, SMTP as an
option; keep server releases current.

Built: README "Start to finish" (five steps, VPS to partner); installer's last step and `admin codes add` print the
two lines to paste into *Join with an invite*; env template current; SMTP (`FF_SMTP_*`, tested against a local fake
server); server-v1.1.0 after merge.

## 3–5

Notes are added here as each section runs.
