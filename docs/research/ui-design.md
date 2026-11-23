# UI design notes: what the platform guidance says, applied to this app

_Written 2026-11-20. Research notes plus a short to-do list. The owner's brief is "still rough,
keep refining". There's no spec beyond that._

## Constraints that shape the choices

- The app is **Views + ViewBinding with Material Components for Android (MDC)**, not Compose.
  Google's guidance points View-based apps at MDC and Compose apps at Compose Material 3.
  So we get M3 colour, type and components, but most of the newest **M3 Expressive** pieces
  (shape morphing, spring motion, new button groups) appear in Compose first. Porting to
  Compose would be a rewrite. Not worth it for a personal-use app.
- Dynamic colour (Material You) is already on, so every colour must come from a **theme colour
  role** (`colorPrimary`, `colorOnSurfaceVariant`, `colorSecondaryContainer` …), never a
  hard-coded hex. 2.5.0 removed the last `textColorSecondary` uses for this reason. Common
  advice is to check a few different wallpapers, since dynamic colour changes the whole
  palette.

## Principles worth applying (from the Android/M3 layout guidance)

1. **Containment shows grouping.** One card per topic, and related controls inside the same
   card (done in 2.5–2.6 for Monitoring, Stats, and Strict mode on Home).
2. **Type hierarchy before decoration.** One `TitleMedium` per card, body text in
   `BodyMedium`/`BodySmall` in `onSurfaceVariant`. Don't use colour just to separate things.
3. **Even spacing.** The guidance says uneven gaps between similar elements make a screen look
   haphazard. Use one card gap (12 dp) and one inner padding (16 dp) everywhere.
4. **Status needs a clear, single answer.** For a protection app, Home should answer "am I
   protected?" in one glance (the hero card), then list what's off and how to fix it, each fix
   one tap away (rows with a coloured dot, as now).
5. **Expressive where it matters.** A "hero moment" is the M3 Expressive idea. Here it fits
   the Home status card and the week chart. Everything else should stay calm.

## Concrete to-dos (ordered by how much they help)

| # | Change | Why |
|---|---|---|
| 1 | Home hero card: one big state ("Protected" / "Needs attention: 2") with the problems listed underneath | Fastest answer to the only question that matters |
| 2 | Settings: group into cards ("Protection", "Monitoring", "Accountability", "Data & logs", "About") and move rarely used items behind "Advanced" | The longest screen; hardest to scan |
| 3 | Monitoring: a "Recent flags" list (time, app, label, score, with **Not a problem** button) | Lets the owner see flags and feeds AI tuning (`research/ai-detection.md` option C) |
| 4 | Empty states with a one-line explanation + action (no rules yet, no partner yet, no stats yet) | Blank cards read as broken |
| 5 | Consistent icons per section (Material Symbols via Vector Asset Studio) | Faster scanning; currently mostly text |
| 6 | Check dark mode + 3 wallpapers after each UI release; screenshot into the PR | Dynamic colour regressions are invisible otherwise |

## Sources

- Material Design on Android (Views → MDC, Compose → Compose M3): https://developer.android.com/design/material
- Layout and content structure: https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure
- M3 Expressive principles (shape, motion, colour, levels of expression): https://developer.android.com/design/ui/wear/guides/get-started/levels-expression
