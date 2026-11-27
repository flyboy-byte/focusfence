# Plan 17: screen-text detection that catches more, and tells partners (2026-11-27)

**Status:** pieces 1-3 built (2026-11-27); piece 4 waits for a log export. Logan, 2026-11-27: on-screen text detection "misses things", and the screenshot check should
have caught the same screen. When explicit text is clearly on screen: report it **and alert** partners.

## How it worked before (3.1.4)

On-screen text capture (off by default) reads the watched apps every 30 s (`TextCapture`, `UrlFilterService`
`TEXT_INTERVAL_MS`). `dns/RiskTerms` matched 13 adult and 10 gambling words as exact whole words, so `p0rn`, `p o r n`,
fancy Unicode letters, plurals and most big sites were missed. Only `node.text` was read, not image and video
descriptions. A hit was a line in the report's tables; partners were never alerted, and a hit didn't start a
screenshot check (it only nudged `FlagPolicy` for a later one).

## Pieces (one PR each)

| # | Piece | Size |
|---|---|---|
| 1 | Matching: disguises (look-alike digits and symbols, spaced letters, Unicode, accents, invisible characters), plurals, about 30 more sites and words; strong vs weak words; read `contentDescription` too | ~2 h |
| 2 | Alert: a strong hit (a strong adult word, or two different weak ones) writes an urgent `svc monitor text` record, at most one per app per 10 min; partners get "Explicit words on screen in <app>: <words>" | ~2 h |
| 3 | A strong text hit starts a screenshot check right away, so the image models see the same screen | ~1 h |
| 4 | Why the screenshot missed it: needs a log export from the phone (analysed locally only, never posted). Likely causes to check: screenshots off for that app, the image only text (the models judge pictures, not words), a score under the owner's threshold, or `FlagPolicy` holding it back | after an export |

## Done

- **Piece 1** (2026-11-27): `RiskTerms.normalize` (NFKC, accents and invisible characters dropped), look-alike
  letters (`a4@`, `e3`, `i1!`, `o0`, `s5$`, `t7`, `l1`, `*` for a vowel), one separator between letters of 4+ letter
  words, plurals; a word only inside a longer hit isn't counted twice; hits are recorded as the plain word.
  `RiskTerms.strong` for piece 2. Tests in `RiskTermsTest` (innocent text: "naked eye", Sussex, Milford, Titanic,
  "rule 3 of 4").
- **Piece 2** (2026-11-27): a strong hit writes `svc monitor text` (detail `porn, milf in Chrome`, urgent), at most
  one per app per 10 min (`TextCapture.ALERT_EVERY_MS`); `Digest.alertText` → "Explicit words on screen: …", an
  important alert. Counter `text_alerts`. Gambling words stay report-only.
- **Piece 3** (2026-11-27): any adult word on screen calls `ScreenCapture.onRiskyText`, which checks that screen with
  the image models now (`trigger=text`, counter `shot_text`), where screenshots already run for the app, at most once
  per 15 s.

## Not doing

- Machine-learning text classification: more battery, and a model's errors are harder to explain to a partner than a
  word list's.
- Blocking or bouncing the app on a text hit: Logan asked for report and alert.
