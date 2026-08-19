# Changelog

All notable changes to Focus Fence are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/), newest first. Dates are from `git log
--date=short`, not invented.

## 2.1.0 — 2026-08-17

- Multi-source ad/tracker and adult-content lists with provenance and categories (HaGeZi, OISD,
  Block List Project, StevenBlack) replacing the single bundled list; stats now show which list
  blocked a domain.
- User allowlist: exempt a domain from list-sourced blocks without touching a rule you set
  yourself.
- Battery/CPU measured for real on hardware: zero wakeup events and a flat drain rate across VPN,
  list and accessibility states — see `docs/benchmarks/`.
- UI rebuilt on a fixed dark theme with bundled IBM Plex typography; a live Home hero (blocked
  counter, 20-minute activity meter, "up next" rule, quick tiles); grouped blocklist cards; a
  stats week chart.

## 2.0.1 — 2026-08-13

- Cut battery draw further: cacheable block answers, upstream resolver and event tuning.
- Fixed unreachable app blocking.

## 2.0.0 — 2026-07-24

- UI restructured into tabs, with a grouped blocklist and a guided domain-add wizard.

## 1.x — 2026-07-23 – 2026-07-24

- Initial release: domain blocking, per-domain daily time limits, section rules (allow one part of
  a site, block the rest), onboarding wizard and in-app guide.
- Strict mode, whole-app blocking, and a stats screen.
- Fixed severe battery drain: blocking tun reads and scoped accessibility events.
- Fixed time budgets being drained by third-party app traffic; added VPN exclusions.
- Fixed the main screen not scrolling once cards outgrew the viewport.
