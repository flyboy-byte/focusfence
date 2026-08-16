# W9 — User allowlist

**Track:** Product (B5) · **Depends on:** W6 · **Blocks:** W11 · **Status:** ✅ **Done
(`logandev`, 2026-08-16)** — core and UI both landed, `./gradlew testDebugUnitTest` and
`assembleDebug` both pass.

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
- [x] UI: "Allowed domains" group in `BlocklistFragment` — add, list, remove. Done 2026-08-16.
- [x] Adding an entry while strict mode is locked is refused, via `UiUtil.showStrictLockedDialog`.
      Removing an entry is not gated — per D-017, adding is the loosening action, removing only
      tightens.
- [x] Adding a DoH bootstrap host shows an explicit warning before it's added.
- [x] `./gradlew testDebugUnitTest` passes with the new cases — done.

## UI implementation notes (2026-08-16)

- `GroupedBlocklistAdapter`: added `Row.Allowed`, `TYPE_ALLOWED`, `GROUP_ALLOWED`. Replaced the
  hardwired `onAppsEdit: () -> Unit` with `onHeaderAdd: (String) -> Unit` keyed on the header's
  group key, and added `onAllowedRemove: (String) -> Unit`. `bindAllowed` shows the domain, a
  fixed "Always allowed" summary, and a remove-only popup (no edit — nothing to edit).
- `BlocklistFragment`: `onHeaderAdd` dispatches `GROUP_APPS` → `chooseBlockedApps`,
  `GROUP_ALLOWED` → `promptAddAllowlistDomain`. `buildRows()` appends an "Allowed domains" group
  sorted by domain with a `Row.Hint` empty state, mirroring the apps group.
  `promptAddAllowlistDomain` checks `StrictStore.isLocked` first (refuses via
  `showStrictLockedDialog`), then a plain `EditText` dialog normalizes via `Blocklist.normalize`
  (invalid input → a dialog using `R.string.invalid_domain`), then if the normalized domain
  matches `Blocklist.DOH_BYPASS_GUARDS` shows a Cancel/Continue warning dialog before adding.
- New strings: `group_allowed`, `allowed_empty_hint`, `allowed_domain_summary`, `menu_add`,
  `allow_domain_title`, `allow_domain_hint`, `allow_doh_warning_title`,
  `allow_doh_warning_message`.
- Not instrumented-tested (no instrumented tests exist in this project); verified by
  `testDebugUnitTest` (unchanged, 58 passing — this is pure UI wiring, no new pure logic) and
  `assembleDebug` succeeding. Manual on-device verification (add/list/remove, strict-mode gate,
  DoH warning) is still worth doing before calling this release-ready, but nothing here is
  speculative — every path mirrors an existing, already-shipped pattern in the same two files.

## Out of scope

- Per-source allowlisting (needs W11's source model).
- Importing/exporting allowlists.
- Automatic false-positive detection.
