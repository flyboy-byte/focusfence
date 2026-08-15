# W9 — User allowlist

**Track:** Product (B5) · **Depends on:** W6 · **Blocks:** W11 · **Status:** 🟡 **Core done on
`logandev` (2026-08-15), UI still open — this file now specs only the UI half.**
**Size:** Remaining work is ~1-2 hours: one dialog, one adapter row type, wiring.

## What's already done (don't redo)

- **O-003 resolved as D-017** in `DECISIONS.md`: an allowlist entry overrides list-sourced blocks
  (ads, DoH guards) only, never a user rule currently in force.
- **`AllowlistStore`** (`app/src/main/java/app/focusfence/data/AllowlistStore.kt`) — `get`/`add`/
  `remove` over a `StringSet` in the `focusfence` prefs file, key `AllowlistStore.KEY_ALLOWLIST`.
  `add()` normalizes through `Blocklist.normalize` at the store boundary.
- **`Blocklist.blockReason(qname, ruleDomain, ruleBlockedNow, allowlist, adHosts)`** — the pure
  D-017 ordering logic, in `dns/` so it's JVM-testable. Covered by five new cases in
  `BlocklistTest.kt` (ad-host exemption, subdomain exemption, rule takes precedence, DoH guard
  exemption, empty allowlist is a no-op).
- **`DnsVpnService`** wired: `@Volatile allowlist` snapshot, loaded in `startVpn()`, refreshed by
  the existing prefs listener on `AllowlistStore.KEY_ALLOWLIST`, and `blockReason()` now delegates
  to `Blocklist.blockReason(...)`.
- `./gradlew testDebugUnitTest` passes with the new cases.

## What's left — UI only

Add an "Allowed domains" group to `BlocklistFragment`'s list. Everything needed already exists as
a pattern elsewhere in the same two files — this is wiring, not design.

**`GroupedBlocklistAdapter.kt`:**
- Add `data class Allowed(val domain: String) : Row()` next to `Row.App`/`Row.Domain`.
- Add a `TYPE_ALLOWED` view type, reusing `ItemDomainCompactBinding` like `Row.Domain`/`Row.App` do
  (`EntryHolder.bindDomain`/`bindApp` are the templates — copy their shape, not their content).
  `bindAllowed` sets `entryTitle` to the domain, a fixed "Always allowed" `entrySummary`, and a
  `moreButton` popup with **remove only** (no edit — there's nothing to edit, unlike a `BlockRule`).
- The header's `showAdd` flow is currently hardwired to `onAppsEdit()` for the apps group
  (`HeaderHolder.bind`, `binding.addButton.setOnClickListener { onAppsEdit() }`). Generalize this:
  replace the `onAppsEdit: () -> Unit` constructor param with `onHeaderAdd: (String) -> Unit` that
  receives the header's `key`, and update the one call site accordingly. `DIFF.areItemsTheSame`
  needs an `Allowed` branch (compare by `domain`, same as `App` compares by `pkg`).

**`BlocklistFragment.kt`:**
- In `onViewCreated`, `onHeaderAdd = { key -> when (key) { GROUP_APPS -> UiUtil.chooseBlockedApps(...); GROUP_ALLOWED -> promptAddAllowlistDomain(...) } }`.
- Add `onAllowedRemove = { domain -> ... }` mirroring `onAppRemove`: check `StrictStore.isLocked`
  first (adding *or removing* isn't the loosening move here — **adding** an allowlist entry is the
  loosening one, per D-017's "one-tap bypass" concern; removing one only tightens, so it does not
  need the strict-lock check — confirm this reading before wiring it, since `docs/work/README.md`
  rule 6 says stop and report if the spec and the code's actual strict-mode pattern disagree).
- In `buildRows()`, add a new group section for `AllowlistStore.get(context)` (sorted), with a
  `Row.Hint` empty state when empty (mirror the apps group's `apps_empty_hint` pattern) and
  `showAdd = true` on its header.
- New function `promptAddAllowlistDomain(context)`: a `MaterialAlertDialogBuilder` with a plain
  `EditText`, normalizing input via `Blocklist.normalize` (reject with the existing
  `R.string.invalid_domain` string on failure), calling `AllowlistStore.add` then `refresh()`. Gate
  it behind `StrictStore.isLocked`/`UiUtil.showStrictLockedDialog` — this is the loosening action.
  If a DoH bootstrap host is being added (`Blocklist.matches(normalized, Blocklist.DOH_BYPASS_GUARDS)`
  or the normalized domain is literally in that set), show a second confirming warning first (per
  D-017: allowed, but the UI must warn since it disables the filter's own bypass protection) —
  a `MaterialAlertDialogBuilder` with a short message and Cancel/Continue is enough, no new store
  state needed.

**New strings** in `res/values/strings.xml`: a group title (mirror `group_apps`), an empty-state
hint (mirror `apps_empty_hint`), a dialog title/hint for the add-domain prompt, and a short warning
message for the DoH-guard case.

## Tests

Core logic is already covered in `BlocklistTest.kt` (see "What's already done"). No further JVM
tests are needed for the UI layer — there are no instrumented tests in this project
(`CLAUDE.md`), so the UI wiring is verified manually: add a domain, confirm it appears and
`Blocklist.blockReason` behavior via a quick manual DNS test if convenient, remove it, confirm
strict mode blocks the add dialog while locked.

## Acceptance criteria

- [x] O-003 recorded as a resolved decision in `DECISIONS.md` (D-017) — done.
- [x] `AllowlistStore` writes to the `focusfence` prefs file and `DnsVpnService`'s listener is
      updated — done.
- [x] Allowlist entries are normalized via `Blocklist.normalize` at the store boundary — done.
- [ ] UI: "Allowed domains" group in `BlocklistFragment` — add, list, remove.
- [ ] Adding an entry while strict mode is locked is refused, via `UiUtil.showStrictLockedDialog`.
- [ ] Adding a DoH bootstrap host shows an explicit warning before it's added.
- [x] `./gradlew testDebugUnitTest` passes with the new cases — done.

## Out of scope

- Per-source allowlisting (needs W11's source model).
- Importing/exporting allowlists.
- Automatic false-positive detection.
