# Parked architecture

> **Tier:** High-level (architecture record) · **Audience:** anyone about to propose one of these ·
> **Use when:** an idea sounds obviously good and you want to know whether it was already
> considered and set down deliberately.

Nothing here is a gap, a TODO, or an oversight. Each item was reasoned through, found to be either
unbuildable within the project's premises or not worth its cost yet, and parked with the reasoning
intact so it doesn't get re-litigated every few months.

Parked is not rejected. If the cost changes — a platform gains an API, a benchmark shows something
we assumed — reopen the item, and say what changed.

Rejections that are permanent (rather than parked) live in `DECISIONS.md`.

---

## P-001 — Mixed-content platforms

*Parked 2026-08-15. Raised by: the adult-content work (D-016, W14).*

**What it is.** Reddit, X, TikTok, YouTube, Telegram, Discord and Imgur serve the content a user
wants blocked from exactly the same hostnames as the content they don't. A domain list can only
answer "all of reddit.com or none of it."

**Why it is parked rather than solved.** The only mechanisms that could separate them are the ones
D-016 rules out permanently: reading page content, classifying images, or inspecting traffic the
app has deliberately arranged not to carry. There is no cheap version. The expensive version costs
the privacy premise, the battery premise, and most of the DNS-only architecture — to buy partial
accuracy on a handful of sites.

**What the app does instead.** Offers the whole domain as an ordinary blockable target, like any
other, and says plainly that it is all-or-nothing. Section rules (the accessibility layer) already
carve paths on sites where paths are visible in a browser address bar, and that is the ceiling.

**What would reopen it.** Nothing currently foreseeable. If a platform ships a real content-rating
API that a client can consult without reading content, that is a different conversation.

**Explicitly not the answer:** per-app content scanning, screenshot classification, an accessibility
service that reads post text. Each of those is a surveillance tool wearing a wellbeing hat, and the
project's own principle (D-010) rules them out regardless of how well they'd work.

---

## P-002 — YouTube restricted mode

*Parked 2026-08-15. Raised by: W14 part 2.*

**What it is.** YouTube exposes `restrict.youtube.com` / `restrictmoderate.youtube.com`, which
looks like it belongs alongside the search-engine SafeSearch rewrites.

**Why it is parked.** It is not equivalent. The search-engine rewrites work purely at DNS level;
YouTube's restricted mode has historically also required a `YouTube-Restrict` HTTP header that a
DNS-layer filter fundamentally cannot set. Whether the hostname alone is sufficient in 2026 is
unverified. Rather than ship a control that might silently do nothing — the worst failure mode this
app has (D-012) — YouTube is out of scope for the first pass of SafeSearch.

**What would reopen it.** Research item 10 answering the header question with a source. If the
hostname alone works, this becomes a one-line addition to the SafeSearch map and should be done
immediately. If it doesn't, it stays parked permanently and the UI should never imply otherwise.

---

## P-003 — Content inspection of any kind

*Parked 2026-08-15 — effectively permanent; see D-016.*

TLS/SNI inspection, full traffic routing, on-device image or text classification, a content-scanning
proxy, keyword filtering. Recorded here because it is the most commonly suggested "obvious next
step" for a blocking app, and because the reasons it is wrong for this project are specific rather
than squeamish:

- It requires observing content, which is the one thing the privacy claim rules out.
- It requires carrying traffic, which is the one thing the battery design rules out.
- It largely cannot work anyway — TLS hides paths, ECH is closing SNI.
- The threat model doesn't need it. The adversary is an impulse, not an adversary.

**What would reopen it, concretely:** `docs/research-handoff.md` queue item 11 asks an agent with
live web access to look at CloudVeil and comparable projects as real prior art for on-device NSFW
classification — cost, accuracy, whether inference genuinely stays local. That research is
explicitly investigative: finding something good does not authorize building it, only gives a
human something concrete to weigh against the four points above.

---

## P-004 — Rewriting the matcher for speed

*Parked 2026-08-15. Raised by: audit 03.*

A trie, bloom filter or compiled matcher instead of `HashSet<String>`. Audit 03 concluded the
existing structure is entirely defensible at ~100k entries, and no benchmark exists to argue
otherwise. Reopen only if W13's harness produces a number showing a real problem — not before.

---

## P-005 — Device Owner provisioning

*Parked 2026-08-15 — effectively permanent; see D-012.*

Device Owner mode would make the app genuinely hard to remove. It also makes the user a managed
device, which is the exact thing the product intent rejects: the owner must stay the owner, keep
ADB, keep sideloading, keep the ability to run other VPNs. Friction, not prevention.
