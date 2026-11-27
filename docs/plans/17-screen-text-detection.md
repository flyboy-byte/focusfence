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
| 5 | Check each picture on screen on its own (image views' bounds from the accessibility tree; 3 squares when none) | ~half a day |
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

- **Piece 5** (2026-11-27): `ScreenCapture.pictureBoxes` reads image and video views' bounds before each shot;
  `dns/ImageRegions.pick` keeps up to 3 (at least 160 px a side, under 60% of the screen, no nested duplicates), or 3
  squares down the screen when there are none and the whole screen scored at least 0.10. Each is classified on its own,
  NudeNet runs on any that is risky alone, and the best result counts. Log field `regions`, counters `region_*`.
  Not yet on a phone: check `region_ms` and the battery in the next export.

- **Piece 6** (2026-11-27, Logan: phrases with an index that's still fast; read deeper after a flag): `RiskTerms`
  is a word index now. The screen is split into words, each disguise is undone per word (look-alikes, `*` for a
  vowel, plurals, runs of single letters), and every 1-3 word run is one hash lookup, so the lists can grow without
  costing more. Words run together ("porn hub") count only when one of them is listed. A real export's 7,418 screens
  replayed at about 0.16 ms each, with 1 alert in 9 days. After adult words or a risky screenshot (`TextCapture.markHot`), that
  app is read every 5 s for 2 min, up to 2,000 nodes and 8,000 characters instead of 400 and 2,000 (counters
  `text_hot`, `text_deep`).

## What a real export showed (2026-11-27, analysed locally, never posted)

Counts only; the export itself stays off the repo.

| Finding | Done |
|---|---|
| Every on-screen "porn" was about quitting or detecting it, none was porn. Pieces 1-2 as first merged would have alerted 47 times in 9 days | `porn` is weak; a screen with quitting or recovery words (`RiskTerms.aboutQuitting`) is recorded but never alerts. Replayed: 1 alert in 9 days |
| Small pictures can't be seen: the whole screen is shrunk to 224 / 320 px, and NudeNet only runs on a risky whole-screen score | Piece 5 |
| Most screens are never captured: about one shot every 1.6-2.5 min per app (Standard is a 90 s mean) | Piece 3 (words trigger a shot); more later |
| Screenshot checks are cheap: about 84 ms per model run, about 6 s CPU per screen-on hour | Leaves room for piece 5 |

## Not doing

- Machine-learning text classification: more battery, and a model's errors are harder to explain to a partner than a
  word list's.
- Blocking or bouncing the app on a text hit: Logan asked for report and alert.
