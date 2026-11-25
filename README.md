# Focus Fence AC

An Android app that blocks sites you don't want to see, and optionally lets people you trust know
when you turn it off.

![license](https://img.shields.io/badge/license-MIT-blue)
![android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![root](https://img.shields.io/badge/root-not%20needed-lightgrey)

[Install](#install) · [What it does](#what-it-does) · [How it works](#how-it-works) ·
[Privacy](#privacy) · [Your own server](#running-your-own-server) · [Building](#building)

---

On its own it's a website blocker. It sets up a VPN on the phone that only handles DNS lookups, so
your normal traffic never goes through it, and blocked sites simply don't resolve. No account, no
server, no root.

Everything else is off until you turn it on: an activity log, uninstall protection, a strict mode
that makes you wait before loosening anything, and accountability partners. Partners get an email
when protection is switched off and can open a report of your activity in their browser. The report
is encrypted on your phone and the key is only in the link you give them, so the server that
delivers it can't read it.

## Install

Grab the APK from [Releases](../../releases) and open it. You'll need to allow installing unknown
apps for your browser or file manager. Every release is signed with the same key:

```
2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671
```

After that the app keeps itself current: once a day it checks for a new release and offers it on the home screen
(you can turn this off in Settings).

The first run sets up the filter and then shows a guide to the optional features, with what each
one does and costs.

> [!IMPORTANT]
> Turn off Private DNS (Settings → Network & internet → Private DNS). While it's on, Android sends
> DNS straight to that provider and the filter can't see it. The app warns you about this.

## What it does

✅ used daily · 🧪 built and tested, not much real use yet · ❌ not yet

| | |
|---|---|
| ✅ | Block domains always, on a schedule, or after a daily time limit |
| ✅ | Allow part of a site and block the rest (`facebook.com/marketplace` but not the feed) |
| ✅ | Ad, tracker and malware lists; adult, gambling, social and others if you want them |
| ✅ | Block apps by sending them back to the home screen |
| ✅ | A log of lookups, apps and protection changes, kept on the phone |
| ✅ | Screenshot checks with an on-device model (optional; images never leave the phone) |
| ✅ | Email partners with instant alerts |
| ✅ | One invite for any partner: opened in a browser they get email reports, pasted into Focus Fence on their phone they see the same report in the app |
| 🧪 | Encrypted partner reports: summary, today or 7 days, changes by day, screenshots, sortable app and site tables |
| 🧪 | Strict mode: a wait, your own PIN, or a partner's code before loosening anything |
| 🧪 | Groups of up to four, and partner unlock codes in the partner's app |
| 🧪 | Sending a partner a heavily blurred screenshot (48 px at most) |
| 🧪 | Uninstall protection: removing the app takes an extra step, and partners are told |
| 🧪 | A notice (or nothing) when you open a blocked adult, gambling or scam site; partners hear if you keep trying; a Block history page |
| ✅ | Updates itself: checks GitHub whenever you open the app, shows what's new, installs when you tap |
| ❌ | Automatic screenshot sending, hiding apps from reports, partner logins |

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
| Alerts ("filter turned off") | Go through the server so it can email partners. No site names |
| Screenshots | Only if you send one, and only blurred |
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

You only need a server for partners. It's a small Kotlin app that stores everything in files and
sends email through Resend (SMS through Twilio or Telnyx is optional). Each person on a server is
separate unless they link up. Every phone on a server joins by invite: the admin page makes one for the person being
filtered, and their phone makes one for each partner.

Setup: [`server/README.md`](server/README.md) and [`docs/plans/04-backend-deploy.md`](docs/plans/04-backend-deploy.md).

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
The screenshot model is [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model).

MIT licensed.
