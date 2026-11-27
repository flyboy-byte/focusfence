# On-device AI detection: what we have, what it gets wrong, what to try next

_Written 2026-11-20 from the first real-world flag. Research notes, not a spec: nothing here is
built unless `STATE.md` says so._

## What runs today

- **Model:** GantMan `nsfw_model`, MobileNet v2 224×224 (MIT), converted to float16 TFLite
  (12 MB, bundled). Five labels: `drawings`, `hentai`, `neutral`, `porn`, `sexy`.
- **Verdict:** `Classification.riskVerdict` takes the highest of the risk labels (`porn`,
  `hentai`, `sexy`) and flags it if it is ≥ the threshold (`MonitorSettings.threshold`,
  default `Classification.DEFAULT_THRESHOLD`).
- **Cost on the Pixel 9 (2.6.1 export):** ~91 ms per image, ~4.5 s CPU per screen-on hour
  for the AI, about 7.6 s/h for screenshots + AI together. The dHash skip avoided 23 of 89
  inferences (26%).

## The first real flag was a false positive

A photo with no nudity scored `porn=0.824, neutral=0.075, drawings=0.054, hentai=0.027, sexy=0.020`.

What that tells us:

- The model has no idea of "male chest" vs "female breast". It sees a lot of skin and a
  face, and its "porn" class is the closest match. A false positive on bare skin is
  expected from this kind of whole-image classifier. Other datasets file topless men under
  "sexy" rather than "porn" (e.g. NsfwSpy's labels), which shows how loosely these classes
  are drawn.
- Published audits of NSFW classifiers found bias by gender, skin tone and age. One found
  **women are flagged more often than men** in ordinary everyday photos (Leu, Nakashima &
  Garcia, FAccT 2024). So the error isn't one-sided: expect false alarms on ordinary photos
  of anyone.
- A single 82% score is **not** evidence. One image is not a measured false-positive rate.
  We need tens to hundreds of reviewed flags before we tune anything with confidence.

## What the first exports taught (2026-11-23)

A lone high score in a photo-gallery app is the typical false alarm; `dns/FlagPolicy` holds lone hits and keeps
sustained runs. One reviewed answer is still not a rate: keep collecting `rev` answers before retuning, and record why in
this file when thresholds change.

## Context bar after explicit words (2026-11-27, plan 17)

Pictures shown next to explicit text should be flagged along with it, but can score well under the body check's 0.50.
Since then, for 3 minutes after words clear enough to alert
partners in an app (`RiskTerms.alertable`), its screenshots run the body check from 0.25 and a picture at 0.35, or one
found suggestive, is a flag (`FlagPolicy.CONTEXT_CHECK_FROM`, `CONTEXT_FLAG`; counter `flag_context`). Untuned. Bounded
by how rare alert-level words are.

Same day: the body check runs from 0.30 instead of 0.50 (`BodyCheck.CHECK_FROM`; very few screenshots score
between them, so it costs little), and a woman in the picture (`BodyCheck.FEMALE`, at least 0.40) adds 0.10 to its score for the context
bar (`FEMALE_BOOST`, counter `body_female`). The boost never makes a flag on its own.

Same day, flag sensitivity (`dns/Sensitivity`): Strict 0.45, Standard 0.50 (default), Relaxed 0.70.
A suggestive picture at or over the bar is a partner flag (`flag_suggestive`); before, suggestive was report-only.
`BodyCheck.CONFIRM` 0.40 → 0.30 (the five false alarms peaked at 0.11). Less strict is a guarded loosening.

## Options, cheapest first

| Option | What it does | Cost | Notes |
|---|---|---|---|
| A. Require persistence (**built 2026-11-23**, `dns/FlagPolicy`) | Report only if 2+ captures from the same app within a few minutes are flagged | ~0 | A lone photo scrolled past is dropped; sustained viewing still reports. Easiest win. |
| B. Per-label thresholds (**built 2026-11-23**: ≥ 0.90 alone, or ≥ 0.75 plus adult words on screen in that app) | e.g. `porn` ≥ 0.90 alone, or `porn` ≥ 0.75 plus a second signal (risky words on screen, adult domain recently) | ~0 | Combines the signals we already log (`text terms`, `url cat`, DNS category). |
| C. "Not a problem" feedback (**built in 2.7.0**: Monitoring → Review screenshots) | The owner marks a flag as wrong or real; logged as `rev` records | small | Builds the dataset needed to tune A/B honestly. No images need to leave the phone. |
| D. Second-stage detector on flagged images only | Run a body-part **detector** (e.g. NudeNet) only when stage 1 flags | Only on flags (rare), so battery stays flat | NudeNet's classes split `MALE_BREAST_EXPOSED` from `FEMALE_BREAST_EXPOSED` and have genital/buttocks classes, so it can tell "bare-skin" from nudity. This cascade design (classifier + object detector) is what Samsung's on-device moderation paper (arXiv 2107.11845) uses. **Check NudeNet's licence and its ONNX→TFLite conversion before bundling** (couldn't confirm the licence from here). |
| E. Swap the base model | A newer classifier (e.g. a CLIP-embedding head, as LAION/NVIDIA use) | larger model, slower | Probably not worth it on a phone until A–D have been tried. |

**Recommendation:** A + B now (no new model, no battery cost), C so tuning is based on real
data, then D if false alarms are still a nuisance once a partner is receiving reports.

## Logging that supports this

Already logged per capture: all five scores, `flagged`, `same`, `trigger`, `ms`, app package.
To add with option C: `review` records (`ok` / `wrong`) keyed by the capture's `t`, so the
diagnostics summary can show precision ("of N flags, M were confirmed").

## Sources

- GantMan nsfw_model: https://github.com/GantMan/nsfw_model
- NudeNet classes: https://pypi.org/project/nudenet/ and https://deepwiki.com/notAI-tech/NudeNet
- Leu, Nakashima, Garcia, *Auditing Image-based NSFW Classifiers for Content Filtering*, FAccT 2024:
  https://facctconference.org/static/papers24/facct24-78.pdf
- *On-Device Content Moderation* (classifier + detector ensemble): https://arxiv.org/abs/2107.11845
- NsfwSpy label definitions: https://www.npmjs.com/package/@nsfwspy/node
