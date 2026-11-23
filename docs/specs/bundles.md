# Filter bundles

**Status: implemented.** Code: `dns/Categories.kt` (bundle set + precedence), `data/CategoryStore.kt`
(policy, tier, download, cache), `CategoriesActivity` (UI). Lists are parsed by the existing
`dns/HostsParser` and matched by `dns/Blocklist`.

## Why bundles, not allow/deny lists

The product choice is curation over hand-maintained lists: the owner toggles a few well-maintained
bundles **Off / Report / Block** and never edits individual domains. The allowlist remains only as
a false-positive escape hatch. Each bundle *labels* a host; its policy decides what happens, so
classification stays separate from enforcement (a list calling a domain "adult" is not by itself
"block forever").

## The set

All lists are HaGeZi (GPL-3.0), downloaded at runtime — never bundled into the APK, so they impose
no license on this repo. On-device defaults use the size-optimised `*-onlydomains` / `.mini`
variants to keep the matcher small; heavier full variants are opt-in.

| id | default | source (mini/default) |
|----|---------|------------------------|
| `security` | **Block** | Multi tier, set by the strictness dial (default Pro mini) |
| `threat` | **Block** | `tif.mini-onlydomains` (full: `tif.medium`) |
| `bypass` | **Block** | `doh-vpn-proxy-bypass-onlydomains` |
| `adult` | Report (since 2.6.0) | `nsfw-onlydomains` |
| `gambling` | Report (since 2.6.0) | `gambling.mini-onlydomains` (full: `gambling`) |
| `social` | Off | `social-onlydomains` |
| `piracy` | Off | `anti.piracy-onlydomains` |
| `scam` | Off | `fake-onlydomains` |

**Security + Threat + Bypass are on by default.** The bypass bundle is the accountability-critical
one: it blocks the DoH/VPN/Tor/proxy bootstrap hosts used to route around the filter, extending the
hard-coded `Blocklist.DOH_BYPASS_GUARDS`.

### Base strictness dial

The `security` bundle's URL comes from `Categories.BaseTier`: Light, Pro (mini, default), Pro
(full), Ultimate (mini). Pro mini is HaGeZi's own recommendation for mobile; Ultimate is aggressive
and may break sites. Changing the tier re-downloads the security list.

## Precedence

`Categories.decide`, highest first: blocking user rule → DoH guards → allowlist (imported-list
false positives only) → a Block-policy bundle → ad list. Matched bundle labels are recorded on the
`dns` log line even when nothing blocks, so the activity record shows what a host *is*.

## Defaults take effect without the UI

`CategoryStore.ensureDefaultsDownloaded` runs on the VPN service's category-reload thread and
fetches any enabled-but-missing bundle, so the default-on security bundles download on first run
without the owner opening the screen. A bundle with no list yet simply contributes nothing until
it is fetched.

## Footprint

Mini variants are built from domains seen on Top 1M/10M lists, so they are a fraction of the full
lists (e.g. Pro full ~250k vs the mini subset). The full threat-intelligence feed is ~2.1M entries,
which is why `threat` defaults to `tif.mini`. The matcher stays a `HashSet`; revisit only if a user
opts into several full lists at once (see `docs/plans/00-roadmap.md` on the matcher).
