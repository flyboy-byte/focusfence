# Focus Fence AC

**A free, open-source site blocker and self-accountability app for Android.** At its core it's a
DNS-only filter that needs no account, no server and no root. On top of that, only if you want, it
keeps an honest record on your phone and lets partners you trust get alerts and an end-to-end
encrypted report that whoever runs the server can't read.

![license](https://img.shields.io/badge/license-MIT-blue)
![android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![kotlin](https://img.shields.io/badge/Kotlin-app%20%2B%20server-7f52ff)
![root](https://img.shields.io/badge/root-not%20needed-lightgrey)

[What works](#what-works-today) · [Install](#install) · [How it works](#how-it-works) ·
[Privacy](#privacy-model) · [Run a server](#run-your-own-server) · [Build](#build) · [Docs](#docs)

---

## The short version

Focus Fence runs a VPN that carries **only DNS**, so your normal traffic never passes through the
app. That keeps it fast and light, and it means blocking happens on the phone with no account. On
top of that sit optional layers, **every one off until you turn it on**: an activity log, in-app
text and screenshot checks with an on-device AI, uninstall protection, strict mode, and partners.

Partners get alerts (filter off, protection removed, unlock requested) by email, and a report they
open in their browser. The report is **sealed on your phone**; the key travels only in the link you
send them, so the server stores ciphertext it can't open.

## What works today

✅ in everyday use · 🧪 built and passing its tests, newer and not yet field-tested · ❌ planned

| | Feature | Notes |
|---|---|---|
| ✅ | **DNS filter**: domains, schedules, daily limits, sections of a site | The original Focus Fence engine. |
| ✅ | **Ad, tracker and threat lists**; DoH-bypass guards | Curated HaGeZi bundles; adult, gambling and others are opt-in. |
| ✅ | **App blocking** | Bounces chosen apps to the home screen (accessibility service). |
| ✅ | **Activity and diagnostics logs** | Every lookup, app, protection change; battery and DNS speed. Local until you export. |
| ✅ | **Restarts itself** after an app update | No Always-on VPN needed. After a reboot too (🧪, not yet seen). |
| ✅ | **Text capture and screenshot AI** (opt-in) | On-device model; images never leave the phone. False flags are common, so Review lets you mark them. |
| ✅ | **Email partners and instant alerts** | Double opt-in. Running against the reference server. |
| 🧪 | **Encrypted partner reports** in the browser | Decrypts with WebCrypto; cross-checked against the phone's sealing. |
| 🧪 | **Strict mode** with a wait, own PIN, or a partner's code | Every unlock request and use alerts partners. |
| 🧪 | **App-to-app partners and groups** of up to 4 | End-to-end through a blind mailbox; members approve each other by fingerprint. |
| 🧪 | **Send a blurred screenshot** to partners | Your choice, per shot. At most 24 px across: a partner never gets a clear image. |
| 🧪 | **Uninstall protection** (device admin) and **managed mode** (device owner) | Managed mode needs adb setup. |
| ❌ | Automatic screenshot sending, report exclusions, a partner login | Planned; see [`docs/roadmap.md`](docs/roadmap.md). |

---

## Install

Download the APK from **[Releases](../../releases)** and open it (allow "Install unknown apps" for
your browser or file manager). Releases are signed with one key; the certificate SHA-256 is:

```
2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671
```

Then open the app: the first run walks you through the filter and ends at a short **Guide**
listing every optional layer, what it does and what it costs. Turn on only what you want.

> [!IMPORTANT]
> Turn **off Private DNS** (Settings → Network & internet → Private DNS). With it on, Android sends
> DNS straight to that provider and the filter never sees it. The app warns you if it's on.

## How it works

```
     your phone
 ┌───────────────────────────┐
 │ apps ─ web, video ────────┼──────────► internet (untouched)
 │  └─ DNS ─► Focus Fence VPN│
 │      block → 0.0.0.0, log │
 │      allow → network DNS  │
 │              (DoH backup) │  sealed report  ┌────────────┐
 │ log → digest → seal (key) ├───────────────► │   server   │
 │                           │  alert lines    │ ciphertext │─► email
 └───────────────────────────┘                 └────────────┘    │
                                     partner's browser opens it ◄┘
                                     with the key from their link
```

- **Only DNS enters the app.** The tunnel routes just its own virtual DNS addresses, so everything
  else uses the normal network. Blocked names get a local `0.0.0.0`; the rest go to your network's
  resolver, with DNS-over-HTTPS as a fallback raced in parallel when the network's DNS is slow.
- **Sections of a site** (allow `facebook.com/marketplace`, block the feed) and app blocking use an
  accessibility service that reads the browser's address bar; DNS can't see paths.
- **Costs are measured, not claimed.** Settings → Logs & diagnostics records the app's own CPU,
  battery drain by screen state and DNS latency. With every optional layer off it is a DNS filter
  and nothing more.

## Privacy model

| What | Where it goes |
|---|---|
| Your blocklist, logs, text and screenshots | Stay on the phone. Exports happen only when you share them. |
| A partner report (sites, categories, app time) | Sealed on the phone (AES-GCM). The server stores it; only a browser holding the key from your link can read it. |
| Alert lines ("Filter turned off") | Sent to the server so it can email your partners. Never site names. |
| Screenshots | Never sent automatically. You can choose to send one, blurred to 24 px. |
| App-to-app partners and groups | End-to-end encrypted; the server is a blind mailbox. |

What the server operator can still see: partners' email addresses, alert lines, and when your
phone checks in. What this doesn't stop: an operator who changes the server code to serve a
key-stealing page. The full reasoning is decision D-023 in [`DECISIONS.md`](DECISIONS.md), and the
public privacy page is in [`docs/site/`](docs/site/).

---

## Run your own server

Partners need a Focus Fence server; the phone works fully without one. The server is a small
Kotlin/Ktor app with flat-file storage, admin pages with email + authenticator sign-in, and
per-person enrollment codes, so every user on a server is independent until they choose to link.

- Configuration reference: [`server/README.md`](server/README.md)
- Layout and deploy procedure: [`docs/plans/04-backend-deploy.md`](docs/plans/04-backend-deploy.md)
- Email via Resend; SMS (Twilio or Telnyx) is optional.

## Build

Requires JDK 17 or 21 and the Android SDK (platform 34).

```sh
./gradlew testDebugUnitTest assembleDebug lint   # app: tests, APK, lint
./gradlew -p server test                         # server tests
```

The debug APK lands in `app/build/outputs/apk/debug/`. It is signed with a debug key, so it won't
install over a release build. `CLAUDE.md` describes the architecture; `AGENTS.md` the working rules.

<details>
<summary><b>Limitations, honestly</b></summary>

<br>

- **DNS-level blocking**, not packet inspection: a cached answer can outlive a new rule until its
  TTL expires, and apps with hard-coded IPs or their own encrypted DNS can bypass it.
- **Time limits are estimates** inferred from lookups while the screen is on.
- **One VPN at a time** on Android, so it can't run beside a real VPN.
- **Don't enable "Block connections without VPN"** for it: the tunnel carries only DNS, so that
  setting cuts everything else. The app flags it.
- **The screenshot AI is wrong a lot**, mostly on photo apps and video pages. That's why images stay
  on the phone and why partners never get a clear one.
- **YouTube and Twitch ads** come from the same hosts as the videos; no DNS blocker can remove them.
- It is friction and honesty, not a cage: you can always turn things off, and partners are told.

</details>

## Docs

| File | What's in it |
|---|---|
| [`STATE.md`](STATE.md) | Where things stand, feature by feature, with how each was verified |
| [`docs/roadmap.md`](docs/roadmap.md) | What's next, in order |
| [`DECISIONS.md`](DECISIONS.md) | Why things are the way they are (D-001 onward) |
| [`CHANGELOG.md`](CHANGELOG.md) | Every change, with the commit to revert |
| [`docs/collaboration.md`](docs/collaboration.md) | How contributors and their agents share `main` |
| [`docs/specs/`](docs/specs/) | Event log schema, friction tiers, device owner, bundles |

## Where it came from

Focus Fence started as a plain website blocker by [@sidewinderzz](https://github.com/sidewinderzz).
[@flyboy-byte](https://github.com/flyboy-byte) took it from there into Focus Fence AC and now maintains
it, much of it built by Claude (Anthropic) working from the plans and decisions in this repo. The app id
is `app.focusfence.accountable`, so it installs separately from the original `app.focusfence`. The screenshot model is
[GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) converted to TensorFlow Lite.

MIT licensed. See [`LICENSE`](LICENSE).
