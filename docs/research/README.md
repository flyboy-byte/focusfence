# Research Intake

Raw output from external deep-research sessions (Claude.ai / ChatGPT research modes), saved here
before being merged into the docs that consume them. See
[`../research-handoff.md`](../archive/research-handoff.md) for the full workflow and the current queue of
open research questions.

File naming: `YYYY-MM-DD-<short-topic-slug>-<engine>.md`. Each file should preserve the exact
prompt used plus the raw response, unedited — corrections and synthesis happen in the consuming
doc (`../approach.md`, `../risks.md`, etc.), not here.

**Ported 2026-11-23 from the retired `shareddev` branch** (raw research, unedited; read with the dates in mind):

| File | Topic | Still relevant to |
|---|---|---|
| `2026-08-17-filter-list-licensing-claude.md` | licences of the filter lists we redistribute | publishing at all (HaGeZi bundles) |
| `2026-08-17-cloudveil-nsfw-classification-claude.md` | how CloudVeil classifies and blocks | the block screen / triage discussion (#13) |
| `2026-08-17-safesearch-hostnames-claude.md` | SafeSearch enforcement hostnames | adult-content category (D-016) |
| `2026-08-17-vpn-coexistence-claude.md` | one VPN slot on Android | why another VPN app turns the filter off |
| `2026-08-17-shizuku-nonroot-claude.md` | Shizuku without root | stronger enforcement (W12) |
| `2026-08-17-usagestatsmanager-claude.md` | UsageStatsManager limits | app time budgets (W10) |
| `2026-08-15/17-signing-key-custody-*` | key custody prior art | background to D-021 |
| `2026-08-17-parked-reaudit-reasoning.md` | re-audit of parked ideas | `docs/parked.md` |
| `2026-08-18-shareddev-decisions.md` | D-017 to D-020 as written there, with status today | partner unlock, D-020 superseded |

Also ported: `docs/benchmarks/` (scripted battery/CPU runs on a Pixel 7, 2026-08-16: a lab baseline beside the real-phone battery logging in `STATE.md`) and `docs/partner-lock-direction.md` (the questionnaire behind D-018 to D-020).

Before that, this project has already been through one research cycle: the five
documents in [`../audits/`](../audits/) were commissioned and delivered on 2026-08-13, before this
convention existed, and were kept there along with the verification pass that checked them against
the source. Read [`../audits/findings.md`](../audits/findings.md) before trusting any of them —
several of their claims about this codebase are wrong.
