# W3 — Add a `LICENSE` file

**Track:** Release · **Depends on:** decision O-001 · **Blocks:** the first release
**Status:** 🔴 **BLOCKED — do not start.** Requires a human decision from both maintainers.

## Why

The repo has no `LICENSE`, `COPYING` or `NOTICE` file. Public source is not a license grant: as
things stand nobody may legally use, redistribute or fork this, and every contribution received so
far arrived with no terms attached.

This is tolerable while two cousins pass an APK between themselves. It becomes a hard blocker the
moment a binary is published for strangers, which is the stated definition of "finished" (D-006).

## Why it's blocked

Copyright is shared — @sidewinderzz wrote the original app, @flyboy-byte has contributed since.
Neither can license it alone. This needs an explicit agreement, not a default.

## The decision (O-001)

Three candidates, analysed in `docs/audits/03-foss-ecosystem.md`:

| License | What it buys | What it costs |
|---|---|---|
| **MPL-2.0** | File-level copyleft — improvements to the filtering engine stay open, while the code can still be embedded in a larger work | Weaker reciprocity than GPL; per-file boundary can confuse contributors |
| **Apache-2.0** | Maximum reuse, explicit patent grant, easiest for anyone to build on | A closed fork can take everything and give nothing back |
| **GPL-3.0** | Aligns with most of the surrounding filtering ecosystem (AdAway, NetGuard, Rethink) | Constrains reuse as a library, which cuts against the stated long-term "another developer could extend this" goal (D-014) |

⚠️ **Do not decide from the executive summary's compatibility table** — it contains errors
documented in `docs/audits/findings.md` §2.8, including marking Apache-2.0 → GPLv3 as incompatible
when it is one-way compatible, and contradicting itself two paragraphs later.

Research queue item 1 (`docs/research-handoff.md`) covers the remaining unknowns.

## Execution, once decided

1. Add the verbatim license text as `LICENSE` at the repo root. Use the canonical text from the
   steward (SPDX / mozilla.org / apache.org) — **do not** retype or summarise it.
2. Add the copyright line naming both maintainers and the year.
3. Add an SPDX identifier to the README's header or a "License" section, e.g.
   `SPDX-License-Identifier: MPL-2.0`.
4. If MPL-2.0: no per-file headers are required, but note in the README that the file-level
   copyleft applies.
5. Do **not** relicense or add headers to individual source files in this work order.

## Acceptance criteria

- [ ] `LICENSE` exists at the repo root with unmodified canonical text.
- [ ] The chosen license is recorded as a resolved decision in `DECISIONS.md` (O-001 → a D-number),
      including who agreed and when.
- [ ] The README states the license.
- [ ] `git diff --stat` shows only `LICENSE`, `README.md` and `DECISIONS.md`.

## Out of scope

- Per-file license headers.
- Contributor licensing (CLA/DCO) — separate decision, not required for a first release.
- Third-party filter-list licensing — that's research queue item 2 and matters for W11.
