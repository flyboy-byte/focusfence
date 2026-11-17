# Focus Fence

An Android app that blocks distracting websites **you** choose — social media, news, anything —
using an on-device VPN. No root, no account, no policy server, no telemetry: every filtering
decision is made on your phone. Lookups that aren't blocked are resolved by a public DNS
resolver, as they would be anyway.

## How it works (and why it's efficient)

Focus Fence registers an Android [`VpnService`](https://developer.android.com/reference/android/net/VpnService),
but it is *not* a real VPN — no traffic is sent to any server. The tunnel is configured so that
**only DNS packets** are routed into the app:

- The VPN interface advertises a virtual DNS server (`10.111.222.3` / `fd66:f0cf:0:1::53`) and
  routes *only* those two addresses through the tunnel. All of your normal traffic (web, video,
  apps) takes the regular network path, untouched.
- The app reads DNS queries off the tunnel. Queries for a blocked domain (or any of its
  subdomains) are answered locally with a cacheable `0.0.0.0` / `::` address (an empty answer
  for other record types), so the site fails to load and the phone doesn't keep re-asking.
- Everything else is relayed to the current network's own DNS servers (the resolver every
  network allows), falling back to DNS-over-HTTPS (Cloudflare `https://1.1.1.1`, Google
  `https://8.8.8.8`) if that fails, and the answer is written back.
- Don't turn on Android's **"Block connections without VPN"** for this app: the tunnel only
  carries DNS, so that setting blocks all other traffic. The app warns if it's on.

Because only DNS (a few small packets per page load) traverses the app, the architecture is
cheap to run by design. The same approach is used by well-known apps like DNS66 and
personalDNSfilter. Rather than claim a number, the app measures it: **Settings → Logs &
diagnostics** records battery drain per screen state, the app's own CPU time, and resolver
latency and failures (see [`docs/specs/event-log.md`](docs/specs/event-log.md)).

## Features

- Add any domain; subdomains are blocked automatically (`reddit.com` also blocks `old.reddit.com`).
- Input is forgiving — paste a full URL and it's normalized to the bare domain.
- **Schedules per domain**: block always, or only during a daily window (e.g. 9:00–18:00).
  Windows may wrap midnight (22:00–06:00).
- **Daily time limits per domain**: e.g. 10 minutes of reddit.com per day, then blocked until
  midnight. Usage is inferred from DNS activity — while you actively use a site your phone keeps
  re-resolving its domains, so each lookup within a few minutes of the last extends an "active
  session" that counts against the budget. Only time with the screen on counts, so background
  app refreshes don't drain it. Accurate to within a minute or two, which is plenty for a fence.
- **Material You**: dynamic color from your wallpaper on Android 12+, themed launcher icon on 13+.
- **Section rules** ("allow only parts of a site"): e.g. allow `facebook.com/marketplace` while
  blocking the rest of facebook.com. DNS can't do this — the feed and Marketplace share one
  hostname and TLS hides the URL path — so these rules are enforced by a small accessibility
  service that reads the browser's address bar and presses Back on blocked pages. Works in the
  major browsers (Chrome, Firefox, Brave, Edge, Samsung Internet, Opera, DuckDuckGo, …); it
  cannot see inside native apps like the Facebook app, so use the allowed section in a browser.
  Requires enabling the "Focus Fence" accessibility service once (the app guides you there);
  address bars are read only to match your own rules, and nothing is stored or transmitted.
- **Ad & tracker blocking**: optional toggle that downloads the
  [StevenBlack hosts list](https://github.com/StevenBlack/hosts) (100k+ ad/tracker/malware
  domains, aggregated from the same sources AdGuard/uBlock use) and blocks those too — for the
  whole device, in every app.
- One-tap preset that adds the major social media sites.
- **Strict mode**: while on, turning blocking off, removing rules, or un-blocking apps requires
  requesting an unlock, waiting 10 minutes, then making changes inside a 5-minute window.
  Adding new blocks is always allowed.
- **App blocking**: chosen apps are bounced to the home screen the moment they open (same
  accessibility service as section rules) — covers native apps DNS can't touch.
- **Stats**: per-day block counts by domain, ad/tracker totals, budget usage, and a 7-day
  trend. Kept on-device only, last 14 days.
- Blocks common browser DoH endpoints and answers the `use-application-dns.net` canary while
  active, so Firefox/Chrome can't silently bypass the filter with their own encrypted DNS.
- Foreground notification + live counter of blocked lookups.
- Works with Android's **always-on VPN** setting for enforcement that survives reboots.

## Logs & diagnostics

Settings → **Logs & diagnostics** keeps two local logs:

- **Activity**: every DNS lookup and whether it was blocked (and by which rule or list), the
  foreground-app timeline (with Usage access), browser addresses (opt-in), enforcement actions,
  and every change to the protection itself: VPN or accessibility stopping, Private DNS
  changes, reboots, log clears.
- **Diagnostics**: battery and CPU samples, DNS latency and failure rates, accessibility event
  volume, and errors. It shows how well the app works and what it costs.

Both stay on the phone until you export them (a zip shared through the system share sheet).
`tools/analyze_logs.py` summarizes an export, and `tools/battery-baseline.sh` takes the
matching adb `batterystats` measurement. With strict mode on, turning recording off or
clearing the logs needs an unlock like any other loosening.

## Building

Requirements: JDK 17+, Android SDK (platform 34). Then:

```sh
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # DNS/packet/blocklist unit tests
```

Or just push to GitHub — the included Actions workflow builds the APK on every push and uploads
it as an artifact (`focus-fence-debug-apk`), which you can download and sideload.

To install on your phone: enable "Install unknown apps" for your file manager or browser,
copy the APK over (or download the CI artifact on the phone), and open it.

## Usage tips

1. Add domains (or tap the presets button), then flip the switch and accept Android's VPN
   consent dialog.
2. For stronger commitment, enable **Settings → Network & internet → VPN → Focus Fence →
   Always-on VPN**. Android will then keep the filter up across reboots.
3. Turn **off Private DNS** (Settings → Network & internet → Private DNS) — when Private DNS is
   on, Android encrypts DNS straight to the configured provider and the filter never sees it.

## Limitations (honest ones)

- **DNS-level blocking**, not packet inspection: a site already resolved and cached may load
  until its DNS TTL expires; apps that hard-code IPs or ship their own DoH stack can bypass it.
  In practice it works well for browsers and most apps, including social media apps.
- **Time limits are estimates**: a DNS filter can't see page focus, so budgets are inferred from
  lookup activity (see Features). Long-lived DNS caches undercount; leaving an app open in the
  foreground without touching it still counts. For exact usage timers, Android's built-in
  Digital Wellbeing offers per-app timers and Chrome site timers, and pairs well with this app.
- YouTube/Twitch ads are served from the same domains as their video content, so DNS-level ad
  blocking (this app, AdGuard DNS, Pi-hole alike) cannot remove them.
- Only one VPN can run at a time on Android — this app can't be combined with a real VPN.
- DNS over TCP is not relayed (queries over the tunnel on TCP/53 are dropped). Mobile clients
  essentially always use UDP; the practical effect is nil.
- This is a productivity aid, not a parental-control system — you can always toggle it off.
  Always-on VPN + app pinning can add friction if you want it.

## Alternatives

If you'd rather not maintain your own app: NetGuard (custom domain blocking is a paid feature),
RethinkDNS, personalDNSfilter (all open source, same VpnService approach), or — with no app at
all — Android's Private DNS pointed at a NextDNS/ControlD config with a denylist. Building your
own keeps the blocklist fully local and under your control.
