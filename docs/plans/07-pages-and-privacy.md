# About / privacy pages and in-app link (built 2026-11-23)

**Update 2026-11-24 (later):** moved. `docs/site` publishes to https://flyboy-byte.github.io/focusfence/ through `.github/workflows/pages.yml`; the app's `about_url` points there (from 2.15); the server no longer serves them at all (Logan's call: no redirect; apps up to 2.14 get a not-found page from that button until they update).

**Decision 2026-11-23:** GitHub Pages is not possible for now (Logan has push, not admin, on `sidewinderzz/focusfence`, and it is private). The pages in `docs/site/` are packaged into the server jar and served at **https://ff.flyboybyte.com/about/** (live); the app's `about_url` points there (unreleased until the next tag). Move to Pages later by changing that one string.

**Built (🧪 CI only):** `docs/site/` has Home, Privacy, Limits and Run-your-own-server pages (static, no scripts); Settings → Accountability card has "About and privacy" (offline summary dialog, then opens `about_url` in the browser). **Not done:** the Pages workflow, enabling Pages (repo admin), the real `about_url` (currently a placeholder guess at `sidewinderzz.github.io/focusfence/`), operator/contact on the privacy page, Licences and Security pages. The privacy page was checked against `TextCapture` (it logs the visible text, 2,000 chars/screen); re-check on each release.

_Requested by Logan 2026-11-23: "add a privacy page and etc as needed into GitHub Pages, linked into Settings."_

## Pages (write only what is true of the app today; each page states what is untested)
| Page | Says | Source of truth |
|---|---|---|
| **Privacy** | What stays on the phone by default (filter, local log); what the optional layers observe (on-screen text, screenshots + on-device AI, with the always-visible indicator) and where it goes (nowhere, unless a server and partners are set up); what a server holds (activity records readable by the server, screenshots encrypted to the partner key, report tokens, email addresses); how long (rotation, 30 days / 300 messages for mailboxes); what a partner or group member can see; how to delete everything; no ads, no analytics, no third-party SDKs | `STATE.md`, `docs/specs/event-log.md`, `server/README.md` |
| **How accountability works** | The layers and their measured costs, partners vs groups, the fingerprint approval, what "recommended" means | `docs/plans/05-group-and-release.md` section 0b |
| **Limits** | DNS-level blocking only, an owner with ADB can still get around it, AI can be wrong (first real flag was a false alarm), Always-on VPN can be dropped by Android, budgets are estimates | README "Limitations", `STATE.md` |
| **Run your own server** | Docker Compose steps, the https requirement, enrollment codes, backups, what to put in `.env` | `server/README.md`, `server/deploy/` |
| **Security** | How to report a problem (a contact address, to be chosen), what the server defends against, the audit history in plain words | `docs/` audit notes |
| **Licences** | The app licence, bundled model and list licences | to be filled once the licence is settled |

## How
- Static pages in `docs/site/` (Markdown or plain HTML, no scripts), published by a small GitHub Actions workflow to **GitHub Pages**.
- App: a Settings row **"About and privacy"** that opens the site URL in the browser (no in-app web view, no network call from the app
  itself). The URL lives in one string resource so it can change.
- The Settings row and a short in-app summary exist even if the site is down, and the privacy text in the app must match the page.

## Caveats and decisions for Logan / @sidewinderzz
- **GitHub Pages on a private repo needs a paid plan**, and enabling Pages, choosing the domain (a `github.io` address or a custom
  one) and the licence are repo-admin decisions for @sidewinderzz. Until then the pages can be written and previewed locally.
- A privacy page is a promise: it must say only what the code does. It should be reviewed against the audit notes before it is
  published, and re-checked on every release that adds an observation.
- Who is named as the operator and contact (the app is "Focus Fence AC", the server is Logan's VPS)? A page for other people's
  use of **his** server needs his name and a contact; a self-hosted server's operator is whoever runs it.
- Order: privacy page and the Settings row first (smallest, most needed before anyone else installs), the rest as needed.
