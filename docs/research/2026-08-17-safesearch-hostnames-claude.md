# Q10 — SafeSearch hostnames, the YouTube header question, and how AdGuard Home does it

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items")
**Date:** 2026-08-17
**Feeds:** W14 part 2 (`docs/archive/work/W14-adult-content-category.md`), P-002 (`docs/parked.md`),
D-016
**Sourcing method:** Google's own admin documentation (support.google.com), AdGuard Home's
actual shipped source and rule files (raw from `AdguardTeam/AdGuardHome` master), and live DNS
resolution of every target hostname from this session. The one thin spot (Pi-hole) is labeled
with its actual confidence.

---

## Exact prompt used

The Q10 prompt from `docs/research/prompts.md` (three parts: current hostnames, the
`YouTube-Restrict` header question, and AdGuard Home / Pi-hole implementation mechanics),
canonical copy there.

---

## Part 2 first, because it unparks P-002: the header is NOT required

Google's own admin documentation ("Restrict YouTube content on your network or managed
devices", support.google.com/a/answer/6214622, fetched 2026-08-17) presents **DNS and HTTP
headers as alternative options** — "Option 1: DNS", "Option 2: HTTP headers" — not as two
required layers. The DNS option, verbatim in substance:

- Add a **CNAME** for exactly these five hostnames: `www.youtube.com`, `m.youtube.com`,
  `youtubei.googleapis.com`, `youtube.googleapis.com`, `www.youtube-nocookie.com`.
- Point them at `restrict.youtube.com` (strict) or `restrictmoderate.youtube.com` (moderate).
- **Do not** CNAME other YouTube hostnames — the doc names `youtube.com` (bare), `s.ytimg.com`,
  `youtu.be`, `googleapis.com`, `youtubeeducation.com` — "YouTube might not function correctly."

Both restricted-mode targets resolve today (checked live 2026-08-17:
`restrict.youtube.com` → 216.239.38.120, `restrictmoderate.youtube.com` → 216.239.38.119).
AdGuard Home ships exactly Google's five-hostname list in its `youtube.txt` safesearch rules
(CNAME to `restrictmoderate.youtube.com`), so the DNS-only approach is not just documented but
deployed at scale in a mature DNS filter.

**Consequence for P-002:** its own exit condition — "If the hostname alone works, this becomes
a one-line addition to the SafeSearch map and should be done immediately" — is met, with a
primary source. YouTube restricted mode moves into W14 part 2's scope as five map entries.
The one honesty note the UI owes: restricted mode is Google's *moderation* filter, not a porn
blocker — it reduces, not eliminates.

## Part 1 — current hostnames, all verified live 2026-08-17

| Engine | Rewrite these queries | To | Verified |
|---|---|---|---|
| Google | `www.google.com` **and every `www.google.<ccTLD>`** — not bare `google.com` | `forcesafesearch.google.com` | resolves → 216.239.38.120 |
| Bing | `www.bing.com`, `edgeservices.bing.com` | `strict.bing.com` | resolves → 150.171.27.16 etc. |
| DuckDuckGo | `duckduckgo.com`, `www.duckduckgo.com`, `start.duckduckgo.com` | `safe.duckduckgo.com` | resolves → 40.89.244.237 |
| Yandex | `www.yandex.com` + country domains + `www.ya.ru` | family VIP `213.180.193.56` (= `familysearch.yandex.ru`, resolved live to exactly that IP) | resolves |
| YouTube | the five hostnames above | `restrict[moderate].youtube.com` | resolves |

Sources: Google's SafeSearch admin page (support.google.com/websearch/answer/186669 — "Set the
DNS entry for www.google.com (and any other Google country or region domains that your users
may use) to be a CNAME for forcesafesearch.google.com"), and AdGuard Home's shipped rule files
(`internal/filtering/safesearch/rules/{google,bing,duckduckgo,yandex,youtube}.txt`, fetched
from master 2026-08-17), which are the practical enumeration of the country-TLD lists. AGH also
ships rules for **Ecosia and Pixabay** — available if W14 ever wants them, not required.

**Correction to W14's sketch table:** it lists `google.com` → forcesafesearch. The correct
target is `www.google.<tld>`; nobody (neither Google's doc nor AGH) rewrites the bare domain,
and Google's YouTube don't-CNAME list shows why the distinction matters — bare domains carry
non-search traffic.

Two details from Google's docs worth keeping:

- Google's hosts-file guidance literally instructs resolving `forcesafesearch.google.com` and
  putting its **IP as an A record for `www.google.com`** (example given: 216.239.38.120). So
  answering the original qname with the safe host's address — no CNAME record at all — is a
  Google-documented pattern, which is exactly the simplification a synthesizing DNS filter
  wants. The VIP presents a certificate valid for the www.google.* hostnames; that is the whole
  design of "SafeSearch VIP".
- The enforcement is *server-side at the VIP*: the client still connects with TLS to the
  hostname it typed. No header injection, no MITM, nothing D-010 objects to.

## Part 3 — how AdGuard Home actually implements it (and Pi-hole's non-answer)

Read from `internal/filtering/safesearch/safesearch.go` (master, fetched 2026-08-17):

- Rules are ordinary filter rules with a `$dnsrewrite` modifier (see the rule files quoted
  above): CNAME rewrites for Google/Bing/DDG/YouTube, **hardcoded A rewrites for Yandex**
  (`NOERROR;A;213.180.193.56`).
- `CheckHost` acts only on qtypes **A, AAAA, and HTTPS** — everything else passes through.
  The HTTPS (type 65) handling matters: modern browsers ask for HTTPS records, and a rewrite
  scheme that only touched A/AAAA would let an HTTPS record's `ipv4hint` bypass the VIP.
  For a mismatched qtype AGH converts the empty result into **NODATA** rather than answering.
- For a CNAME rewrite, AGH does **not** rewrite the relayed upstream response for the original
  qname. It short-circuits: the result carries `CanonName`, the proxy resolves the safe
  hostname itself, and the client receives a synthesized answer. A source TODO ("Consider
  switch back to resolving CNAME records IPs and saving results to cache") shows they have
  used both variants of the same idea — resolve-the-safe-host-and-answer, never
  patch-the-original-response.
- Results are cached with a TTL (`CacheTTL`) so the safe-host lookup isn't repeated per query.

**Design consequence for W14 part 2, and it shrinks the work order:** the sketch budgeted for
"rewriting an answer's owner name, including handling DNS name compression pointers in the
response" as the real cost. The mature implementation never does that. The simpler, prior-art
shape is: on a SafeSearch hit for qtype A/AAAA, relay a *fresh query for the safe hostname*
upstream (the plumbing `forward()` already has), then synthesize a response to the client —
original question section, answer = optional CNAME (qname → safe host) plus the safe host's
address records, TTL from upstream, cached briefly. Answer HTTPS-type queries for rewritten
hostnames with NODATA. No compression-pointer surgery anywhere. Fail-closed behaviour from the
work order stands: if the safe hostname doesn't resolve, block, don't fall through.

**Pi-hole:** no native SafeSearch implementation was found — Pi-hole's approach in practice is
user-added local CNAME records (dnsmasq `cname=` entries), which is configuration, not code.
*Confidence label:* based on absence from Pi-hole's documentation and the community-guide
pattern, not on an exhaustive read of the FTL source — GitHub code search was unavailable to
this session. It does not affect any W14 decision (AGH is the load-bearing prior art); noted
for completeness.

## What this settles

- **W14 part 2 is unblocked**, with a corrected hostname map, a simpler implementation shape
  than the sketch budgeted for, and a new requirement surfaced (handle qtype HTTPS).
- **P-002 unparks into W14** by its own written exit condition, five map entries plus the
  restrict-vs-restrictmoderate choice (recommend strict for this app's purpose; AGH defaults
  to moderate — that is a product choice to make consciously in W14, not silently).
- Yandex: use the family VIP as an A rewrite (AGH's shape). If a CNAME is preferred for
  consistency, `familysearch.yandex.ru` resolves to the same VIP today.
