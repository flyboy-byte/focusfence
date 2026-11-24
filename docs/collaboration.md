# Working on Focus Fence together without conflicts

> **Tier:** How we work · **Audience:** @flyboy-byte, @sidewinderzz and any Claude session either of us
> runs · **Decided in:** D-022 (2026-11-23), superseding D-005.

## The shape

- **`main`** is the only long-lived branch. Everything else is a short task branch.
- **Task branch:** one per task, from fresh `main`. Any name works (`logan/...`, `sidewinderzz/...` or a Claude
  session's own `claude/...` name). Open a **draft PR right away**: it is the claim that tells the other
  person, and their Claude, that this area is taken.
- **Merge** by PR with CI green, within a day or two. Longer work: update from `main` daily.
- **Release**: bump `versionCode`/`versionName` in a small release PR, merge, tag `vX.Y.Z-accountable` on
  `main`. Never bump the version in a feature PR, and never tag anything but `main`.
- **Signing**: one key for every release (D-021). CI signs from the repo secrets on tag pushes only.

## Before a Claude session starts

Tell it: work from `origin/main`, read `STATE.md` and `docs/HANDOFF.md` first, open a draft PR early, do not
push to `main`, do not tag. Both of us run agents that read the same docs, so stale docs on `main` mislead
both sides at once: update them in the same PR as the change.

## Hot files (where conflicts actually happen)

| File | Why it conflicts | Habit |
|---|---|---|
| `DECISIONS.md` | both sides pick the next number (D-017 clashed on 2026-11-23) | take the number when merging, not when drafting; say in the PR which number you used |
| `STATE.md` | everyone updates the status table | small, separate edits; one line per change |
| `app/src/main/res/values/strings.xml` | appended to at the end by everyone | add strings next to their feature's group, not at the bottom |
| `fragment_settings.xml`, `SettingsFragment.kt` | every feature adds a row | say in the PR you touched Settings |
| `vpn/DnsVpnService.kt` | filter, relay and logging all live here | keep changes small; mention it in the PR title |
| `app/build.gradle.kts` | version numbers | only the release PR changes the version |

## Repo settings (admin, @flyboy-byte since the transfer on 2026-11-24)

1. ~~Default branch → `main`~~ done 2026-11-24.
2. Optional: protect `main` (require a PR, or the `Android CI` build). Nothing about PR *content* is enforced;
   see AGENTS.md, "Pull requests: awareness, not rules".
3. ~~Squash merging only; automatically delete head branches~~ done 2026-11-24.

## Branch cleanup on 2026-11-23

Deleted (each fully contained in `main` or in an archive tag, checked before deleting):
`claude/project-thread-ngusk5`, `claude/project-thread-ulwmqj`, `claude/logging-and-accountability-plan`,
`logandev` (identical to `shareddev`), `shelbydev` and `claude/focus-fence-session-s72elg` (both already inside
`shareddev`).

Archive tags (restore any branch with `git branch <name> <tag>`): `archive/shareddev-2026-08-18`,
`archive/logging-and-accountability-plan`.

Still to do: `shareddev` holds 29 commits that never reached `main` (allowlist W9, multi-source lists W11,
battery benchmark W13, a UI rebuild, research, D-017 to D-020). Port the useful parts by PR, one feature at a
time, then delete it. `ccr-810b8122-68jbvi` goes once #11 is closed (it predates and contradicts D-021). The old
default branch goes once the default is `main`.
