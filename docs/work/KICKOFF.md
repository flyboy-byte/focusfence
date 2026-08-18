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
2. docs/work/NEXT.md — the standing queue. This answers "what do I work on." **Read its
   "Settled — do not re-litigate" section closely** — a prior session already reasoned through
   D-017's allowlist/rule ordering, W11's shape, O-002's draft resolution (R1), the parked-item
   re-audit (R2), and six research questions (Q2/Q3/Q4/Q8/Q10/Q11), each cited from a primary
   source. Re-deriving one of these from scratch instead of reading the linked file is wasted
   work at best and a worse answer than what's already on file at worst.
3. DECISIONS.md — skim the headers (D-001..D-01N). These are settled; don't re-litigate them.
4. docs/parked.md — ideas already considered and rejected. Check here before proposing
   anything that sounds like an "obvious" addition — it's probably already here with the
   reasoning for why not.

Branch discipline (also in docs/work/README.md rule 1): work on your own personal branch
(ask which one is yours if unclear — logandev and shelbydev exist), never shareddev or main
directly. Merge into shareddev yourself once ./gradlew testDebugUnitTest passes. Never touch
main.

Before picking an item: work out whether you actually have live web access (can you fetch a
real URL right now, not just reason from training data). This changes what "unblocked" means for
you specifically — NEXT.md's sections are labeled by what they need. If you don't have web
access, skip straight past anything requiring it (don't stop just because the first section you
see needs browsing you don't have) and look for a "no web access needed" section instead. If
literally nothing in NEXT.md is unblocked for a session with your capabilities, that's a real
stopping point — say so plainly rather than inventing scope.

Pick the top unblocked item in docs/work/NEXT.md's queue and execute it. When you finish an
item: check it off in NEXT.md with the date and branch, run the test suite, commit, and merge
into shareddev if it's genuinely done and tested.

**Then do not stop. Go back to NEXT.md, re-read it (your last merge may have unblocked
something), and pick the next unblocked item.** Keep going, item after item, in this same
session, until one of these is actually true:
- NEXT.md has no unblocked item left — everything remaining is genuinely waiting on a human
  decision (say which one, and why) or another item that hasn't landed yet.
- You hit something that needs a decision only a human can make (a one-way door, a legal
  question, anything DECISIONS.md would need a new numbered entry for) — stop, write down the
  fork, and ask, rather than guessing and moving on.
- You've been at this long enough that quality is dropping — better to report a clean stopping
  point than to grind out a sixth item sloppily. Use judgment; there's no fixed item count.

A session that does one item and reports back has NOT finished the task — it's done the first
fifth of it. "I completed an item" is not the exit condition; "the queue is actually empty or
actually blocked" is. If you're unsure whether to keep going, keep going.

If you hit a research item and have live web access, execute it directly per the instructions
at the top of docs/research-handoff.md rather than waiting for a human browser session — that
counts as an item like any other, keep looping afterward.

Report back only once, at the end: what you did across every item, what you decided, what's
still open — the same way you'd write a commit message: why, not just what. Don't send a
progress report after each individual item; work through the queue silently and summarize once
at the end.
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
- **The loop instruction exists because it was needed, not preemptively.** 2026-08-15: a session
  given this prompt ran exactly one queue item (Q9), reported back, and stopped, despite several
  more zero-dependency items sitting ready in NEXT.md. The prompt didn't say not to stop, so it
  stopped — that's a prompt bug, not a judgment failure on the session's part. Fixed by making the
  exit condition explicit (queue empty or genuinely blocked, not "I did a thing").
- **The web-access check exists for the same reason.** By 2026-08-16 every zero-dependency
  code item was done, leaving a queue where most remaining rows need live web access. A session
  without it (a plain high-context chat with no browsing) would hit those rows, correctly fail to
  do them, and — without being told to look further — might stop entirely instead of noticing
  `docs/reasoning-prompts.md`'s R1/R2 need no browsing at all. Sessions differ in what they can
  actually do; the prompt now says to account for that before concluding nothing's available.
- **The "Settled" pointer exists because it almost didn't happen.** A 2026-08-17 session with
  live web access cleared the entire research backlog in one sitting (R1, R2, six research
  questions, and W11 built end to end) — a genuinely good outcome, but it means the very next
  session to open this repo will find a queue full of *answered* questions and *shipped* work,
  not open ones. The risk isn't a session ignoring NEXT.md; it's a session reading a row, having
  its own opinion, and quietly overriding a verified finding instead of treating it as load-
  bearing. The app is now functionally complete — see the queue's Polish section — so most new
  sessions from here on are working *on top of* settled decisions, not making new ones.
