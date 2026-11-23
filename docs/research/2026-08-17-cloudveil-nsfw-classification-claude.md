# Q11 — CloudVeil and on-device NSFW classification as prior art (investigative)

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items")
**Date:** 2026-08-17
**Feeds:** P-003 (`docs/parked.md`), D-016. **Investigative only — authorizes building nothing.**
**Sourcing method:** CloudVeil's actual GitHub organization (github.com/cloudveiltech — the
prompt's "verify the actual project" resolved to this org, not "CloudVeil"), their own website's
filtering page, the Filter-Windows repository README, and web search for the measurable claims
of the open-source on-device NSFW classifier ecosystem. Confidence labels inline.

---

## Exact prompt used

The Q11 prompt from `docs/research/prompts.md` (four questions: on-device vs server, measured
battery cost, error rates, and what Android capability a classifier actually needs to see
content), canonical copy there.

---

## Headline: the prior art *strengthens* P-003's rejection, on the exact axis P-003 predicted

CloudVeil — the most prominent commercial filter in this space — **does not do on-device NSFW
image classification at all.** Its architecture, read from its own repositories and site, is
built from the two mechanisms this project has permanently rejected, plus one it already uses:

1. **Traffic interception on desktop.** The flagship repo is `Filter-Windows` — "HTTP/S Content
   Filter for Windows 7 and newer" (their description) — supported by their forks of `goproxy`
   ("An HTTP proxy library for Go", MITM-capable), a .NET **ICAP server** (the protocol for
   content adaptation inside a proxy), and `gotun2socks` (routing device traffic into a proxy).
   Filtering HTTPS *content* in a proxy structurally requires a locally trusted root
   certificate — that is inherent to TLS, not something read from their source (labeled:
   structural inference, high confidence). This is precisely the "content-scanning proxy"
   branch of P-003/D-016's rejected list, and D-010 rejects it on principle for Focus Fence.
2. **Owning the renderer.** Their mobile strategy is *forked apps*: CloudVeilMessenger
   (a Telegram fork for Android/iOS/desktop, actively maintained — last pushes August 2026) and
   a filtered browser — their site: "accessing their website in the filtered browser instead
   will enable text filtering." Content filtering happens where CloudVeil *is* the app
   rendering the content. Not available to Focus Fence by definition.
3. **DNS, for exactly what Focus Fence uses DNS for.** Their own site: CloudVeil "will enforce
   YouTube's own SafeSearch moderate setting using DNS" (Windows/macOS/router) and via their
   Android VPN. The one mechanism they share with this project is the one this project already
   has — independent corroboration of W14's approach, from a vendor whose desktop product can
   intercept traffic and still chooses DNS for SafeSearch.

No NSFW-classification repository exists in their org at all (org listing read 2026-08-17).

## The generic on-device classifier ecosystem, against Q11's four questions

- **On-device is real** in the narrow sense: nsfw-filter (a browser extension) and NSFWJS run
  TensorFlow.js models client-side with no upload; quantized MobileNetV2-class models compress
  to roughly **3 MB (from ~14 MB)**. So "inference genuinely local" exists — *inside a
  browser or inside the app that already has the pixels.*
- **Accuracy:** ~90% (small) to ~93% (mid-size MobileNetV2) per NSFWJS's own numbers; a newer
  ViT-based model (Marqo, 384px) claims 98.56% *on its own dataset*. Documented false-positive
  classes: medical imagery, classical art, swimwear. **No independent benchmark corroborates
  any of these numbers** (labeled: vendors' own figures). At browsing volume, 7–10% error on a
  blocking decision means constant misfires in both directions.
- **Battery: no published measurements found.** Per-image inference on mobile is tens of
  milliseconds, but the honest cost is the *pipeline*: classifying what a user sees means
  continuously acquiring frames and running inference on them. Nobody in this ecosystem
  publishes a mAh/day number for that (labeled: absence-of-evidence finding — searched, not
  proven absent).
- **The Android capability question — the decisive one.** A classifier needs pixels. On stock
  Android, a third-party app can get another app's pixels only by: being the renderer (a
  browser/fork — CloudVeil's route), intercepting traffic (MITM proxy + root CA — CloudVeil's
  other route), or capturing the screen (MediaProjection with persistent capture notification,
  or accessibility screenshotting) — i.e. *watching the user's screen*, the exact surveillance
  shape P-001/P-003 already name as disqualified regardless of efficacy. **None of these is
  compatible with a DNS-only VPN architecture: the app has arranged never to carry the bytes a
  classifier would need.** That is not an implementation gap; it is the design working.

## Verdict for P-003 / D-016

P-003's own reopen text asked whether real prior art shows a cheap on-device version this
architecture could adopt. It shows the opposite: the going commercial approach is
interception-or-forked-apps, the on-device classifiers that exist live inside renderers, and
the only mechanism CloudVeil and Focus Fence share is DNS. Q11's framing said a
"fundamentally different traffic-observation model" finding should be stated plainly:
**that is the finding.** D-016's four reasons all survive contact with the prior art; the
battery reason is now also backed by W13's measured baseline (which content-carrying would
forfeit), and the feasibility reason by CloudVeil's revealed preference for DNS wherever it
doesn't own the renderer.

**Recommendation to the human this returns to:** P-003 stays parked/rejected; no change to
D-016. Nothing here starts any work. If anything, W14 gained a small confidence boost — the
DNS SafeSearch mechanism is what the interception vendor uses too.
