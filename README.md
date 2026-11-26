# Focus Fence AC

Accountability for porn on Android. It blocks adult sites, can check the screen for nudity, and tells people you
trust when protection changes. It can block anything else you choose too.

![license](https://img.shields.io/badge/license-AGPL--3.0-blue)
![android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![root](https://img.shields.io/badge/root-not%20needed-lightgrey)

[Install](#install) · [What it does](#what-it-does) · [How it works](#how-it-works) ·
[Privacy](#privacy) · [Your own server](#running-your-own-server) · [Feedback](#feedback) · [Building](#building)

---

On its own it's a filter. It sets up a VPN on the phone that only handles DNS lookups, so your normal traffic never
goes through it, and blocked sites simply don't resolve. Adult sites, ads and the usual ways around a filter are
blocked from the start. No account, no server, no root.

The first run offers the rest, and you can turn each on later: screen checks (on-device AI, clear images never leave
the phone), a strict mode that makes you wait before loosening anything, uninstall protection, and accountability
partners. Partners get an email
when protection is switched off and can open a report of your activity in their browser. The report
is encrypted on your phone and the key is only in the link you give them, so the server that
delivers it can't read it.

## Install

Grab the APK from [Releases](../../releases) and open it. You'll need to allow installing unknown
apps for your browser or file manager. Every release is signed with the same key:

```
2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671
```

After that the app keeps itself current: whenever you open it, it checks for a new release and offers it on the home
screen, where it downloads and installs when you tap (you can turn this off in Settings).

The first run is five short steps: what it's for, turn on the filter, screen checks, strict mode, and a partner if you
want one. Each can be skipped.

> [!IMPORTANT]
> Turn off Private DNS (Settings → Network & internet → Private DNS). While it's on, Android sends
> DNS straight to that provider and the filter can't see it. The app warns you about this.

## What it does

✅ used daily · 🧪 built and tested, not much real use yet · ❌ not yet

| | |
|---|---|
| ✅ | Block domains always, on a schedule, or after a daily time limit |
| ✅ | Allow part of a site and block the rest (`facebook.com/marketplace` but not the feed) |
| ✅ | Adult sites blocked from the start; extra lists for ads, malware, scams, gambling, social and piracy |
| ✅ | Block apps by sending them back to the home screen |
| ✅ | A log of lookups, apps and protection changes, kept on the phone |
| ✅ | Screenshot checks with on-device AI (optional; clear images never leave the phone) |
| 🧪 | Two-step screenshot check: a whole-picture model, then NudeNet looks for exposed or covered body parts, so faces and skin don't count as porn |
| 🧪 | Partners by code, QR code or email invite, nothing to paste: they read your report in the Focus Fence app or a browser, and get alerts as notifications or email |
| 🧪 | Dead man's switch: partners hear within about an hour if the phone stops checking in, and what happened once it's back |
| 🧪 | Encrypted partner reports, about 15 minutes fresh: status, needs-attention, one timeline, day-by-day bars, screenshots, sortable app and site tables |
| 🧪 | Strict mode: a wait, your own PIN, or a partner's code before loosening anything |
| 🧪 | Partners hear when protection is loosened: filter off, adult list set to Report, screen checks off, leaving the server |
| 🧪 | Ask a partner: one tap asks for an OK instead of waiting out strict mode; any one partner can answer |
| 🧪 | Flagged screenshots sent to partners automatically, heavily blurred (48 px at most), with a note from you |
| 🧪 | Suggestive images (lingerie, swimwear) noted in the partner report |
| 🧪 | Uninstall protection: removing the app takes an extra step, and partners are told |
| 🧪 | A notice (or nothing) when you open a blocked adult, gambling or scam site; partners hear if you keep trying; a Block history page |
| ✅ | Updates itself: checks GitHub whenever you open the app, shows what's new, installs when you tap |
| 🧪 | Feedback, feature requests and bug reports from Settings, as GitHub issues with your versions filled in |
| ❌ | Hiding apps from reports, partner logins, scanning on-screen text for explicit content (planned) |

What's next is in [`docs/roadmap.md`](docs/roadmap.md).

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

The VPN only routes its own DNS address, so it costs very little battery. Allowed lookups go to your
network's normal DNS server, with DNS-over-HTTPS as a backup when that's slow. Blocking part of a
site, and blocking apps, uses an accessibility service, because DNS only sees the domain name.

## Privacy

| | Where it goes |
|---|---|
| Blocklist, logs, captured text, screenshots | Stay on the phone unless you export them |
| Partner report | Encrypted on the phone. The server stores it but can't open it |
| Alerts ("filter turned off", "phone went silent") | Go through the server so it can email partners and app partners can fetch them. No site names |
| Screenshots | Only flagged ones (an exposed body part), or one you send; always blurred to 48 px, never the clear image |
| App partners | Read the same sealed report as email partners; alerts and unlock codes are end-to-end encrypted between the two apps |

The server does see partners' email addresses, which phone is whose partner, the alert text, and when your phone checks
in. Someone
who runs a server and changes its code could serve a page that steals the key; the reasoning is in
D-023 in [`DECISIONS.md`](DECISIONS.md).
The full privacy page is at https://flyboy-byte.github.io/focusfence/privacy.html.

## Limitations

- It blocks at the DNS level. Apps with their own encrypted DNS or hard-coded IPs can get around it,
  and a cached lookup can outlast a new rule for a while.
- Time limits are estimated from lookups while the screen is on.
- Android allows one VPN at a time, so it can't run next to another VPN.
- Don't turn on "Block connections without VPN" for it. That setting would cut off everything that
  isn't DNS.
- The screenshot model gets things wrong fairly often, especially in photo apps.
- YouTube and Twitch ads come from the same servers as the videos, so DNS blocking can't remove them.
- You can always turn it off. If you have partners, they'll hear about it.

## Running your own server

You only need a server for partners. There's no public one: run your own, or join someone's by invite. It's a small
Kotlin app that stores everything in files and sends email through Resend or any SMTP account. Each person on a server
is separate unless they link up. The operator makes an enrollment code for the person being filtered (admin page or
`admin codes add`), and their phone invites each partner with a code, a QR code or an email.

Install the newest `server-v` release (Docker image or a tarball with a systemd installer). The app and server keep
working with each other two protocol versions back, and CI holds both to that (D-041).

Setup, start to finish: [`server/README.md`](server/README.md).

## Feedback

In the app: Settings → Feedback & bug reports. Or open an issue here and pick bug report, feature request or feedback.
The repository is public, so don't paste logs or screenshots: they show what you browse. Security problems go through
private reporting ([`SECURITY.md`](SECURITY.md)).

## Building

JDK 17 or 21 and the Android SDK (platform 34).

```sh
./gradlew testDebugUnitTest assembleDebug lint   # app
./gradlew -p server test                         # server
```

Debug builds use a debug key, so they won't install over a release.
[`STATE.md`](STATE.md) has where everything stands and [`CHANGELOG.md`](CHANGELOG.md) every change.
To report a security problem, see [`SECURITY.md`](SECURITY.md). Contributing: [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Credits

Focus Fence started as a plain website blocker by [@sidewinderzz](https://github.com/sidewinderzz).
[@flyboy-byte](https://github.com/flyboy-byte) built the accountability side and maintains it now.
The screenshot models are [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) (MIT) and
[NudeNet](https://github.com/notAI-tech/NudeNet) (AGPL-3.0).

Copyright (c) 2026 sidewinderzz and Focus Fence contributors. Licensed under the
[GNU Affero General Public License v3.0](LICENSE). Releases up to 2.21.0 were published under MIT and stay MIT.
