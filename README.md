# Focus Fence

An Android app that blocks distracting websites and apps **you** choose — using an on-device DNS
filter. No root, no account, no policy server, no telemetry.

📋 **[Project review — status, what's next, what's parked](https://claude.ai/code/artifact/78d38a65-6891-4169-be8c-b75b5761339a)**

---

## What it does

- **Blocks domains you pick**, including every subdomain. Paste a full URL — it's normalised for you.
- **On a schedule**, a daily time budget, or always.
- **Blocks apps**, not just sites.
- **Allows parts of a site** — e.g. Facebook Marketplace while the feed stays blocked.
- **Strict mode** makes loosening anything take 10 minutes, so a 1 a.m. impulse doesn't win.
- **Stays on your phone.** Every filtering decision is made on-device, and nothing is uploaded.

## Install

**From a release (recommended):** download the latest signed APK from
[Releases](https://github.com/sidewinderzz/focusfence/releases). Requires Android 8.0 (API 26) or
newer.

1. Download `focus-fence-<version>.apk` from the release you want.
2. **Verify it before installing.** The release description publishes the file's SHA-256 —
   check it against what you downloaded:
   ```sh
   sha256sum focus-fence-<version>.apk
   ```
   The two hashes must match exactly. This confirms you got the file that was actually built from
   the tagged source, not a tampered copy.
3. Enable "Install unknown apps" for whichever app you used to download it (Settings → Apps →
   [that app] → Install unknown apps), then open the APK to install.

**From source:**

```sh
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # unit tests
```

Requires JDK 17+ and Android SDK platform 34. Pushing to GitHub also builds an APK and uploads it
as the `focus-fence-debug-apk` artifact, which you can download on the phone and open directly.

**Why sideloading, not Play:** the app isn't on Google Play. See "Permissions and trust" below for
what that means for verifying you got the real thing.

## Setting it up

1. Add domains (or tap the presets button for the major social sites), then flip the switch and
   accept Android's VPN consent dialog.
2. **Turn off Private DNS** — Settings → Network & internet → Private DNS. With it on, Android
   encrypts DNS straight to your provider and the filter never sees it.
3. For stronger commitment, enable **Always-on VPN** — Settings → Network & internet → VPN →
   Focus Fence. The filter then survives reboots.

---

## Features in detail

### Rules

| Rule | What it does |
| --- | --- |
| **Always** | Blocked whenever the filter is on |
| **Daily window** | e.g. 9:00–18:00. May wrap midnight (22:00–06:00) |
| **Daily budget** | e.g. 10 minutes of reddit.com, then blocked until midnight |
| **Section allowlist** | Allow `facebook.com/marketplace`, block the rest of the site |

**About budgets.** A DNS filter can't see which tab you're looking at, so usage is *inferred*: while
you're actively using a site your phone keeps re-resolving its domains, and each lookup within a few
minutes of the last extends an active session. Only time with the screen on counts, so background
refreshes don't drain it. Accurate to a minute or two — plenty for a fence, not a stopwatch.

**About section rules.** DNS can't do these: the feed and Marketplace share one hostname, and TLS
hides the path. So they're enforced by a small accessibility service that reads the browser's
address bar and presses Back on blocked pages. It works in the major browsers (Chrome, Firefox,
Brave, Edge, Samsung Internet, Opera, DuckDuckGo and others) but **cannot see inside native apps**
— use the allowed section in a browser. Address bars are matched against your own rules and
nothing is stored or sent anywhere.

### App blocking

Chosen apps get bounced to the home screen the moment they open, via the same accessibility
service. This covers native apps that DNS can't touch.

### Ads and trackers

An optional toggle downloads the [StevenBlack hosts list](https://github.com/StevenBlack/hosts) —
100k+ ad, tracker and malware domains, a widely used and actively maintained aggregation of
hosts-format sources — and blocks them device-wide, in every app.

### Strict mode

While on, turning blocking off, removing rules or un-blocking apps requires requesting an unlock,
waiting 10 minutes, then acting inside a 5-minute window. **Adding new blocks is always allowed.**

### Bypass guards

Browsers ship their own encrypted DNS, which would sail straight past the filter. Focus Fence
blocks the common DoH endpoints and answers the `use-application-dns.net` canary while active, so
Firefox and Chrome fall back to system DNS.

### Everything else

Per-day stats by domain, ad/tracker totals, budget usage and a 7-day trend — kept on-device, last
14 days. Material You dynamic colour on Android 12+, themed icon on 13+. Foreground notification
with a live counter.

---

## How it works

Focus Fence registers an Android
[`VpnService`](https://developer.android.com/reference/android/net/VpnService), but it is **not a
real VPN** — no traffic goes to any server. The tunnel is built so that only DNS packets ever enter
the app:

1. **Only two addresses are routed.** The interface advertises virtual DNS servers
   (`10.111.222.3` / `fd66:f0cf:0:1::53`) and routes *only those*. All your normal traffic — web,
   video, apps — takes the ordinary network path and never touches this app.
2. **Blocked queries are answered locally** with a null address (`0.0.0.0` / `::`) carrying a short
   TTL, so the site fails to connect. The answer is deliberately *cacheable*: an uncacheable
   NXDOMAIN made clients re-query constantly and cost real battery.
3. **Everything else is relayed** to a public resolver (Cloudflare `1.1.1.1`, Google `8.8.8.8` as
   fallback) over a socket that bypasses the tunnel, and the answer is written back.

Because only DNS — a few small packets per page load — passes through the app, the overhead is very
small. The same architecture is used by DNS66 and personalDNSfilter.

**On privacy, precisely:** there is no account, no policy server and no telemetry, and every
filtering decision happens on your device. DNS lookups for allowed domains still go to a public
resolver, exactly as they would without the app installed.

## Limitations (honest ones)

- **DNS-level blocking, not packet inspection.** A site already resolved and cached may load until
  its TTL expires, and apps that hard-code IPs or ship their own DoH stack can bypass it. In
  practice it works well for browsers and most apps, social media included.
- **Time budgets are estimates**, for the reason described above. Long-lived DNS caches undercount;
  leaving an app open in the foreground without touching it still counts. For exact timers,
  Android's Digital Wellbeing pairs well with this app.
- **YouTube and Twitch ads can't be blocked** — they come from the same domains as the video
  itself, which defeats every DNS-level blocker equally.
- **Only one VPN at a time**, per Android. This can't run alongside a real VPN.
- **DNS over TCP isn't relayed** (TCP/53 queries are dropped). Mobile clients essentially always
  use UDP, so the practical effect is nil.
- **This is a productivity aid, not parental controls.** You can always turn it off. Always-on VPN
  plus app pinning adds friction if you want it.

## Permissions and trust

Focus Fence asks for `VpnService` **and** `AccessibilityService` — the same permission pair
malware wants, because it's genuinely powerful. Being suspicious of an app that asks for both,
sideloaded from outside Play, is the correct instinct. "It's open source" doesn't clear that on
its own — almost nobody actually reads the source, and a downloaded APK isn't obviously the same
binary as the repo. Here's exactly what each permission is used for, what it can technically see,
and what it deliberately does not do:

**VPN (`VpnService`).** Routes only DNS packets addressed to two synthetic local addresses the
tunnel advertises itself — everything else (web pages, video, every other app's traffic) takes
the normal network path and never enters this app at all. No traffic is ever sent to any server
this project operates; there is no such server. Allowed DNS queries are relayed to a public
resolver (Cloudflare `1.1.1.1` or Google `8.8.8.8`), the same place they'd go without the app
installed.

**Accessibility (`AccessibilityService`).** Reads the address bar of a small set of known browsers
to enforce per-path rules (e.g. allow `facebook.com/marketplace`, block the rest of the site), and
watches for a blocked app coming to the foreground so it can bounce it home. It does not read page
content, form fields, or anything you type, and it's only ever registered for the specific
packages you've actually configured a rule for — not every app on the device.

**How you can check this yourself, not just take it on faith:** the app is DNS-only by
construction — the VPN's routing table literally only contains the two addresses above (`/32` and
`/128`), which you can confirm by reading `vpn/DnsVpnService.kt`. Releases are built by GitHub
Actions CI directly from the tagged commit (see `.github/workflows/release.yml`), so the binary
you download is traceable back to the exact source that produced it, and the SHA-256 in the
release lets you confirm you received that build unmodified.

## Alternatives

If you'd rather not maintain your own app: NetGuard (custom domain blocking is a paid feature),
RethinkDNS or personalDNSfilter — all open source, same `VpnService` approach. Or with no app at
all, point Android's Private DNS at a NextDNS/ControlD config with a denylist. Building your own
keeps the blocklist fully local and under your control.

## Project docs

The [project review](https://claude.ai/code/artifact/78d38a65-6891-4169-be8c-b75b5761339a) is the
readable summary. In the repo, in more detail:

| | |
| --- | --- |
| [`FRAMEWORK.md`](FRAMEWORK.md) | Where the project is, phase by phase |
| [`DECISIONS.md`](DECISIONS.md) | Numbered log of *why* |
| [`docs/parked.md`](docs/parked.md) | Ideas considered and deliberately set down |
| [`docs/work/`](docs/work/) | Self-contained work orders — start here to build something |
| [`CHANGELOG.md`](CHANGELOG.md) | What shipped, by version |
| [`RELEASING.md`](RELEASING.md) | How a signed release actually gets cut |

## Licence

MIT — see [`LICENSE`](LICENSE). `SPDX-License-Identifier: MIT`
