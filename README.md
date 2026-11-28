# Focus Fence AC

Accountability for porn on Android. It blocks adult sites, can check the screen for nudity, and tells the people you
trust when protection changes. It can block anything else you choose too.

[![release](https://img.shields.io/github/v/release/flyboy-byte/focusfence?label=release)](../../releases/latest)
[![downloads](https://img.shields.io/github/downloads/flyboy-byte/focusfence/total?label=downloads)](../../releases)
![license](https://img.shields.io/badge/license-AGPL--3.0-blue)
![android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![root](https://img.shields.io/badge/root-not%20needed-lightgrey)

[Install](#install) · [What works](#what-works) · [Screen checks](#screen-checks) ·
[Partners and groups](#partners-and-groups) · [How it works](#how-it-works) · [Privacy](#privacy) · [Your own server](#running-your-own-server) · [Building](#building)

---

**On its own it's a filter.** It runs a VPN on the phone that only handles DNS lookups, so your normal traffic never
goes through it, and blocked sites simply don't resolve. Adult sites, ads and the usual ways around a filter are
blocked from the start. No account, no server, no root.

**The rest is optional**, offered on the first run: screen checks (on-device AI; clear images never leave the
phone), a strict mode that makes you wait before loosening anything, uninstall protection, and **partners**. A partner
is someone who sees your report and hears when protection changes. A **group** is up to six people who all see each
other. Reports are encrypted on your phone, and the key goes from your phone to each partner's device by itself, so
the server that relays them can't read them.

---

## Install

Get the APK from [Releases](../../releases/latest) and open it. You'll need to allow installing unknown apps for your
browser or file manager. Every release is signed with the same key:

```
2ca769a523a0cf41bcba67abadc71c5abe5daddfeb130665f853c84476098671
```

After that it keeps itself current: when you open it, it checks for a new release and offers it on the home screen,
where it installs when you tap (Settings can turn this off). The first run is five short steps, each skippable: what
it's for, the filter, screen checks, strict mode, and a partner.

> [!IMPORTANT]
> Turn off Private DNS (Settings → Network & internet → Private DNS). While it's on, Android sends DNS straight to
> that provider and the filter can't see it. The app warns you about this.

---

## What works

✅ in daily use or checked in a real log · 🧪 built and tested, barely used on a phone · ❌ not built.

| | Feature | Notes |
|---|---|---|
| ✅ | **Block sites** always, on a schedule, or after a daily time limit | Time limits are estimated from lookups while the screen is on |
| ✅ | **Allow part of a site** and block the rest | `facebook.com/marketplace` but not the feed (accessibility service) |
| ✅ | **Block lists** | Adult and bypass lists always on; extra lists for ads, malware, scams, gambling, social and piracy |
| ✅ | **Block apps** | Sends them back to the home screen |
| ✅ | **An on-phone record** of lookups, apps and protection changes | Exported only when you choose |
| ✅ | **Screenshot checks** with on-device AI | Optional; two models, so faces and skin alone don't count. [How it decides](#screen-checks) |
| ✅ | **No blind spots after switching apps** | Checked in a real log: longest unread stretch 53 s (it was 10 min before 3.1.5) |
| ✅ | **Updates itself** from GitHub releases | Shows what's new, installs when you tap |
| 🧪 | **Small pictures checked on their own** | Each picture on screen (up to 3) gets the models' full detail; after anything risky, that app is checked every 10 s for 2 minutes |
| 🧪 | **Flag sensitivity**: Strict, Standard, Relaxed | Making it less strict waits, or needs a partner's PIN, and partners are told |
| 🧪 | **Partners** by code, QR code or email invite | Tried on two phones: "quick and seem less". They read your report in the app or a browser |
| 🧪 | **Alerts** when protection loosens or the phone goes silent | Filter off, adult list set to Report, screen checks off, leaving the server; silence within about an hour |
| 🧪 | **Encrypted report**, about 15 minutes fresh | Status, needs-attention, one timeline, day-by-day bars, app and site tables |
| 🧪 | **Strict mode** | A wait, your own PIN, or a PIN a partner holds, before loosening anything |
| 🧪 | **Ask a partner** | One tap asks for an OK instead of waiting out strict mode; any one partner can answer |
| 🧪 | **Flagged screenshots to partners** | Automatic, blurred to 48 px at most, with a note from you |
| 🧪 | **Crash and connection records** in the export | Why the app stopped, network switches, and how lookups, sync and updates failed. Stays on the phone |
| 🧪 | **Explicit words on screen** | Optional; reads the apps you choose. Sees through `p0rn`, `p o r n` and fancy letters, knows phrases and written erotica. Clear hits alert partners (by app, the words stay in the report) and start a screenshot check; a page about quitting doesn't alert. Password and code fields are never read |
| 🧪 | **Uninstall protection** | Removing the app takes an extra step, and partners are told |
| 🧪 | **Apps that skip the filter** | For games or maps that break behind it; anything that could show adult content waits in strict mode. Up to 3.1.7 newly ticked apps aren't saved: fixed in the next release |
| 🧪 | **Block notices and history** | A notice (or nothing) on a blocked adult, gambling or scam site; partners hear if you keep trying |
| 🧪 | **Groups** of up to 6, everyone sees everyone | Tried on two phones; full flow tested across app and server code |
| ❌ | Hiding apps from reports; removing one person from a group | Planned or parked: [`docs/parked.md`](docs/parked.md), [`docs/plans/`](docs/plans) |

---

## Screen checks

Optional, and only in the apps you pick. Everything below runs on the phone; a clear screenshot never leaves it.

```
 screenshot ─► whole screen + up to 3 pictures ─► MobileNet: how risky?
                                                       │
                           risky enough to look closer ┘
                                      ▼
                NudeNet: exposed body part? covered (lingerie, swimwear)?
                                      │
   exposed ──────────────────────────►├─► partner flag: blurred picture,
   covered, at your sensitivity ─────►┤   after a 10-min note window
   risky, next to explicit words ────►┘

 screen text ─► word index ─► explicit? ─► alert (app name only),
                                           look closer for 3 min
```

| Flag sensitivity | A suggestive picture flags from | Nudity flags |
|---|---|---|
| Strict | 45% | always |
| **Standard** (default) | 50% | always |
| Relaxed | 70% | always |

- **Why two models:** the first one alone can call an ordinary face or bare skin "porn". A flag now needs the second
  one to find something.
- **Context counts:** for 3 minutes after explicit words in an app, its pictures are looked at from 25% and flag from
  35%, so covers and thumbnails on such a page are caught with the text. A woman in the picture adds a little to its
  score; it never flags on its own.
- **Tuned on real use:** word and score changes are replayed against a real log, on the log owner's own computer,
  before they ship. Logs never go in this repo; the reasoning is in [`docs/research/ai-detection.md`](docs/research/ai-detection.md) and
  [`docs/plans/17-screen-text-detection.md`](docs/plans/17-screen-text-detection.md).

---

## Partners and groups

Partners need a Focus Fence server ([run one](#running-your-own-server), or join someone's). Nothing is pasted.

| | How it starts | Who sees whom | Alerts reach them |
|---|---|---|---|
| **Partner** | Partners → Add a partner: an 8-character code, its QR code, or an email invite | They see your report | In their Focus Fence app, or by email if they joined from the emailed link |
| **Group** | Partners → Start a group: one code for up to 6 people | Everyone sees everyone | Each member's app, "Important only" to start |

- Whoever uses an invite first becomes that partner: no approval step. You see every partner and each of their
  devices in the list, and joining is never silent.
- A partner can read on several devices (their phone, a laptop browser). Each device makes its own key, and your
  phone seals the report key to it at its next check-in, within about a minute while an invite is open.
- Removing a partner, or someone leaving, makes your phone start a new report key, so they can't read on.
- A phone that joins someone's report is on that server too, so it can be watched later without an enrollment code.
- Limits: 5 partners of your choosing, 4 groups, 20 people watching one phone in all, 4 devices per partner.

---

## How it works

```
       your phone
 ┌──────────────────────────────┐
 │ apps ─ web, video ───────────┼────────────► internet (untouched)
 │  └─ DNS ─► Focus Fence VPN   │
 │      block → 0.0.0.0, log    │
 │      allow → network's DNS   │
 │              (DoH backup)    │ sealed report ┌─────────────┐
 │ log → report → seal ─────────┼─────────────► │   server    │
 │ report key ─ sealed to each ─┼─────────────► │ ciphertext, │
 │   partner device's own key   │  alert lines  │ public keys │
 └──────────────────────────────┘               └──────┬──────┘
                                                       ▼
                        partner's app or browser: opens its copy of the key,
                        then the report; alerts by notification or email
```

The VPN only routes its own DNS address, so it costs very little battery. Allowed lookups go to your network's normal
DNS server, with DNS-over-HTTPS as a backup when that's slow. Blocking part of a site, and blocking apps, uses an
accessibility service, because DNS only sees the domain name. Architecture in depth: [`CLAUDE.md`](CLAUDE.md) and
[`docs/architecture.md`](docs/architecture.md).

---

## Privacy

| | Where it goes |
|---|---|
| Blocklist, logs, captured text, screenshots | Stay on the phone unless you export them |
| Partner report | Encrypted on the phone; the server stores ciphertext it can't open |
| The report key | Sealed on your phone to each partner device's own public key; the server relays it sealed |
| Alerts ("filter turned off", "phone went silent") | Through the server, so it can notify partners. Never a site name |
| Explicit words on screen | The words stay in the encrypted report; the alert says only which app ("Explicit words on screen in Chrome") |
| Screenshots | Only flagged ones ([how](#screen-checks)), or one you send; blurred to 48 px, never the clear image |
| Password, PIN and code fields | Never read, even after "show password" |

The server does see who partners whom (names, groups, and email addresses for email invites), the alert text, and when
your phone checks in. Someone who runs a server and changes its code could serve a page that steals a browser's key;
the reasoning is D-023 and D-042 in [`DECISIONS.md`](DECISIONS.md). Full privacy page:
https://flyboy-byte.github.io/focusfence/privacy.html.

---

## Limitations

- It blocks at the DNS level. Apps with their own encrypted DNS or hard-coded IPs can get around it, and a cached
  lookup can outlast a new rule for a while.
- Android allows one VPN at a time, so it can't run next to another VPN. Don't turn on "Block connections without
  VPN" for it: that cuts off everything that isn't DNS.
- The screenshot models judge pictures, not stories: a cover the first model scores very low is never looked at
  closely. Their accuracy on real explicit content is untested; the bars are reasoned, not measured.
- Screen checks are samples: a screenshot about every 1.5 minutes per app (faster for 2 minutes after anything risky),
  and on-screen text every 30 seconds. Something seen for a few seconds can be missed.
- On-screen text is matched against word and phrase lists, not understood. Disguises are caught; new slang isn't until
  it's added.
- YouTube and Twitch ads come from the same servers as the videos, so DNS blocking can't remove them.
- Apps you let skip the filter aren't filtered at all.
- You can always turn it off. If you have partners, they'll hear about it.

---

## Running your own server

You only need a server for partners. There's no public one: run your own, or join someone's by invite. It's a small
Kotlin app that stores everything in files and sends email through Resend or any SMTP account. The operator gives the
person being watched an enrollment code (admin page, or `admin codes add`); partners and group members join with the
invite or group code instead.

Install the newest `server-v` release (Docker image, or a tarball with a systemd installer). App and server speak a
numbered protocol and say which side needs updating when they're out of range (D-041). The current partner system is
level 2; apps from before it are refused with "update the app".

Setup, start to finish: [`server/README.md`](server/README.md).

---

## Feedback

In the app: Settings → Feedback & bug reports. Or open an issue here (bug report, feature request or feedback). The
repository is public, so don't paste logs or screenshots: they show what you browse. Security problems go through
private reporting ([`SECURITY.md`](SECURITY.md)).

## Building

JDK 17 or 21 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug lint   # app
./gradlew -p server test                         # server
tools/check.sh                                   # all of it, plus the public-docs check
```

Debug builds use a debug key, so they won't install over a release. [`STATE.md`](STATE.md) has where everything
stands, [`CHANGELOG.md`](CHANGELOG.md) every change, [`CONTRIBUTING.md`](CONTRIBUTING.md) how to help.

## Credits

Focus Fence started as a plain website blocker by [@sidewinderzz](https://github.com/sidewinderzz).
[@flyboy-byte](https://github.com/flyboy-byte) built the accountability side and maintains it now.
The screenshot models are [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) (MIT) and
[NudeNet](https://github.com/notAI-tech/NudeNet) (AGPL-3.0).

Copyright (c) 2026 sidewinderzz and Focus Fence contributors. Licensed under the
[GNU Affero General Public License v3.0](LICENSE). Releases up to 2.21.0 were published under MIT and stay MIT.
