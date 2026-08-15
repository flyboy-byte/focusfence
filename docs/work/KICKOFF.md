# KICKOFF — paste this into a fresh agent session to start it cold

> **Tier:** Execution · **Audience:** whoever is about to open a new Claude/Fable/Opus session
> against this repo (Shelby, an autorun session, anyone) · **Use when:** starting a session that
> has never seen this repo, or has no memory of it.

This is not a doc to read for background — it's a **literal prompt block**. Copy the fenced section
below, paste it as the first message in the new session, and the session should be able to pick up
real work within one reply. Everything after the fence is *why it's shaped this way*, for whoever's
sending it.

## The prompt

```
You're picking up work on Focus Fence, an Android app (Kotlin, no DI/Compose) that blocks
distracting websites/apps via a DNS-only VpnService, fully on-device — no accounts, no
servers, nothing leaves the phone.

Before doing anything else, read these four files in order:
1. CLAUDE.md — architecture, hard constraints, commands. Read the whole thing.
2. docs/work/NEXT.md — the standing queue. This answers "what do I work on."
3. DECISIONS.md — skim the headers (D-001..D-01N). These are settled; don't re-litigate them.
4. docs/parked.md — ideas already considered and rejected. Check here before proposing
   anything that sounds like an "obvious" addition — it's probably already here with the
   reasoning for why not.

Branch discipline (also in docs/work/README.md rule 1): work on your own personal branch
(ask which one is yours if unclear — logandev and shelbydev exist), never shareddev or main
directly. Merge into shareddev yourself once ./gradlew testDebugUnitTest passes. Never touch
main.

Pick the top unblocked item in docs/work/NEXT.md's queue and execute it. If everything is
blocked, say so rather than inventing scope — NEXT.md is deliberately closed. If you hit a
research item and have live web access, execute it directly per the instructions at the top
of docs/research-handoff.md rather than waiting for a human browser session.

When you finish an item: check it off in NEXT.md with the date and branch, run the test
suite, commit, and merge into shareddev if it's genuinely done and tested. Report back
concisely — what you did, what you decided, what's still open — the same way you'd write a
commit message: why, not just what.
```

## Why it's shaped this way

- **Four files, in that order, nothing else mandatory.** A cold session that reads the entire
  `docs/` tree before doing anything burns context and money proving it understands the project
  instead of doing the project. CLAUDE.md + NEXT.md + decision headers + parked list is the minimum
  that prevents the two most expensive mistakes: breaking a battery-motivated hack that looks like
  dead code, and re-proposing something already rejected with reasoning on file.
- **It's a prompt, not a doc pointer**, because the whole point is zero back-and-forth to get
  started — paste it, session reads the four files itself, session starts working. If it were just
  "go read CLAUDE.md," someone still has to decide what to say next.
- **No status dashboard, no ceremony.** `docs/work/NEXT.md`'s checkboxes are the entire coordination
  mechanism between sessions. This file exists only to make starting a *new* session cheap; it isn't
  a second source of truth to keep in sync with anything.
