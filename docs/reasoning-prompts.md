# Reasoning prompts — for deep, judgment-heavy questions, not fact lookup

> **Tier:** Meta · **Audience:** whoever has a high-context/high-reasoning model session
> (Opus, Fable, extended-thinking) and wants to point it at something harder than a research
> lookup · **Use when:** the question is a real trade-off or a "does this decision still hold"
> re-audit, not something citations alone would answer.

`docs/research-handoff.md` and `research/prompts.md` are for **external facts** a model
shouldn't fabricate. This file is the opposite case: questions this project already has all the
context for, in its own docs, where the value is in a model actually reading the whole tree and
reasoning hard about it — not searching the web. Good fit for a session with a big context window
and room to think, bad fit for burning a fast/cheap model's limited reasoning budget.

**Procedure:** paste the whole prompt (it tells the model which files to read first). Save the
output to `docs/research/YYYY-MM-DD-<topic>-reasoning.md` same as external research, and bring it
back to a human before acting — these are framed as recommendations, not authorizations, same
discipline as the investigative research items.

---

## R1 — Signing-key custody: the actual decision, not just prior art

```
Read CLAUDE.md, DECISIONS.md (all of it, especially O-002), docs/infrastructure.md, and
docs/work/W4-release-signing.md and W5-release-workflow.md in the Focus Fence repo.

This project has a real, blocking, one-way-door decision to make: who holds the Android
release-signing keystore for a two-person open-source project (one repo owner/admin, one
regular contributor), distributed via GitHub Releases, with CI already running via GitHub
Actions.

If docs/research/ contains a file answering research queue item 9 (prior art from
RethinkDNS/NetGuard/personalDNSfilter/AdAway), read it and use it as input — but the job
here isn't to summarize that, it's to reason through this project's actual situation:

1. What are the realistic failure modes of each option — CI holds the key as a GitHub
   Actions secret and signs automatically vs. a human signs by hand each release — for a
   two-person project specifically? Consider: what happens if the repo owner (the only
   admin) becomes unavailable; what happens if the GitHub org/account is compromised; what
   happens if the contributor needs to cut a release and the owner is unreachable; how does
   each option interact with the fact that admin rights currently sit with one person only.
2. Is there a hybrid that's actually better than either pure option for this specific
   two-person case — e.g. CI signs, but the raw keystore is also escrowed offline somewhere
   only the owner can reach, so CI compromise doesn't equal total loss but doesn't require
   a human in the loop for every release either?
3. Give a specific, concrete recommendation — not "it depends" — including exactly what
   Logan (owner) and the contributor should each end up holding/doing, and what to do the
   day the keystore is generated (backup location, who verifies the backup actually works).

Write the output as something that could be pasted directly as the resolution text for
O-002 in DECISIONS.md, in that file's existing style (see D-005 or D-016 for the shape:
what was decided, the reasoning, the consequence).
```

---

## R2 — Re-audit the parked/rejected list against current reality

```
Read docs/parked.md, DECISIONS.md, docs/work/README.md's board, and docs/work/NEXT.md in
the Focus Fence repo in full.

Several ideas have been deliberately parked or permanently rejected (P-001 through P-005
in parked.md; D-012, D-014, D-016 and others in DECISIONS.md as permanent rejections).
Each was reasoned through against the project's stated principles: DNS-only routing
(D-001), no content inspection (D-010/P-003), least-invasive-mechanism (D-010), threat
model is impulse not a determined adversary (D-012), no premature general policy engine
(D-014).

Since those were parked, W6 and W7 have actually shipped (rule evaluation extracted to
dns/, time-bounded app blocking landed) and W9's core (an allowlist) has landed. That's
more real, working code than existed when several of these decisions were made.

Your job: re-read each parked/rejected item with that current state in mind, and answer,
for each one, honestly:
1. Does the original reasoning still hold given what's actually been built since? Don't
   assume yes by default — actually check whether the code that shipped changes any
   premise the rejection depended on.
2. Is there a cheaper version of any parked idea that the current architecture (post-W6/
   W7/W9) makes newly possible, that wasn't possible or wasn't obviously cheap when it was
   parked?
3. Flag anything where you're not confident either way rather than forcing a verdict — a
   correct "I can't tell without X" is more useful than a confident guess.

This is explicitly a sanity check, not a proposal to reopen anything. If everything still
holds, say so plainly — a clean re-audit that changes nothing is still a useful output,
not a wasted one. Write findings as a short table: item, still holds / reopen-worthy /
uncertain, one line of reasoning each. Bring it back to a human (don't act on "reopen-
worthy" by starting to build anything) — the same discipline research queue item 11
already uses for exactly this reason.
```
