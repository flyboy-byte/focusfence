# Q3 — Shizuku on a non-root device in 2026 (for W12)

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items")
**Date:** 2026-08-17
**Feeds:** W12 (`docs/archive/work/W12-shizuku-enforcement.md`)
**Sourcing method:** primary sources — Shizuku's own README and official user manual
(shizuku.rikka.app), the Shizuku-API repo README, the AOSP Shell package manifest (the
authoritative list of what the ADB shell uid may do — Shizuku's README itself points there),
and Maven Central metadata for the API library. One recency check could not be completed from
this session and is flagged plainly rather than guessed.

---

## Exact prompt used

The Q3 prompt from `docs/research/prompts.md` (five questions: non-root suspend/disable
capability, permission model, setup friction, reboot persistence, wrapper-library maintenance),
canonical copy there — not duplicated here.

---

## Headline answer for W12

**The feature is feasible on non-root, and the friction shape D-012 wants is still there.**
The ADB shell uid holds both `SUSPEND_APPS` and `CHANGE_COMPONENT_ENABLED_STATE`, so a
Shizuku-privileged app can suspend or disable other packages without root; and Shizuku on
non-root still must be restarted after every reboot ("due to system limitations", per its own
manual), which keeps this mechanism firmly "friction, not prevention" — but also creates a
reboot-shaped hole W12 must handle loudly, not silently.

## Findings, question by question

### 1. Can a Shizuku app suspend/disable another package without root? Yes.

Shizuku's model is binder-forwarding: the app's system-API calls execute with the privileges of
the uid that started the Shizuku server — root, or **shell (uid 2000) when started via ADB**
(Shizuku README, "How does Shizuku work?"). What shell may do is defined by the permissions
granted to the Shell package, and the README explicitly points at the AOSP Shell manifest as
the reference. That manifest (fetched from `aosp-mirror/platform_frameworks_base`, master,
2026-08-17) grants shell — among much else:

- `android.permission.SUSPEND_APPS` (line 337) — `PackageManager.setPackagesSuspended`,
  the mechanism behind "suspended" greyed-out apps with an unsuspend dialog.
- `android.permission.CHANGE_COMPONENT_ENABLED_STATE` (line 278) —
  `setApplicationEnabledSetting` on other packages (`pm disable-user`).
- `android.permission.FORCE_STOP_PACKAGES` (line 207) — kick an already-running app out.
- also `PACKAGE_USAGE_STATS` (line 172), incidentally relevant to W10.

So on a genuinely non-rooted, ADB-activated device, **suspension is available**, and it is the
right primitive for W12 (suspend ≫ disable for a self-control app: reversible, visually
obvious, doesn't break push/data the way disable can).

### 2. Permission/API mechanics

- The calling app itself needs only the Shizuku runtime permission (a normal runtime dialog
  once Shizuku runs) — the privileged operation executes server-side as shell. Shizuku README
  "Attention" #1: check `Shizuku.getUid()`/`checkPermission` because "ADB has limited
  permissions and different on various system versions."
- `setPackagesSuspended` is not public SDK API — reaching it goes through the binder wrapper
  (`ShizukuBinderWrapper` around `IPackageManager`) plus the hidden-API bypass the README's
  Attention #2 points at (LSPosed/AndroidHiddenApiBypass). This is the standard, documented
  pattern for Shizuku apps, not an exotic hack, but it *is* a hidden-API dependency — pin and
  test it per Android release.

### 3–4. Setup friction and the reboot question

From the official user manual (shizuku.rikka.app/guide/setup, fetched 2026-08-17), verbatim
where quoted:

- Non-root activation on Android 11+ is **on-device via Wireless debugging** — "This startup
  method does not require a connection to a computer." Enable Developer options → Wireless
  debugging; **"Pairing (only needs once)"**; then start Shizuku.
- **"Due to system limitations, the startup steps need to be performed again after each
  reboot."** — the restart-after-reboot requirement is *still current* in the live manual.
  Pairing survives; the start step does not.
- Below Android 11 (this app's `minSdk` is 26 = Android 8.0), non-root activation requires a
  computer (`adb`) after every reboot — materially worse friction. W12 should treat Shizuku as
  practically an Android-11+ feature and say so in the UI rather than promising it to 8.x users.

**Design consequence, and it cuts both ways.** The reboot hole keeps Shizuku on the right side
of D-012 (an owner can always get out from under it by rebooting — friction, not prevention).
But it also means enforcement silently stops after every reboot until the user restarts
Shizuku — and *silent non-enforcement is this app's named worst failure mode*. The W12 sketch
already requires automatic fallback to the accessibility mechanism plus a visible
which-mechanism indicator; this research confirms that requirement is not optional polish but
the load-bearing half of the feature.

### 5. Wrapper library and maintenance

- Maven Central `dev.rikka.shizuku:api`: latest **13.1.5**, last published **2023-09-21**
  (maven-metadata.xml, fetched 2026-08-17). A stable API surface that has not needed releases
  is consistent with Shizuku's design (the API is a thin binder shim), but a ~3-year-old last
  release is a real data point, not nothing.
- **Flagged as unverifiable from this session:** the Shizuku *app's* release recency.
  github.com HTML (releases page/atom feeds) is blocked by this environment's proxy —
  raw.githubusercontent.com works, release listings don't. The docs site is live and currently
  maintained in three languages, and the download page still routes through GitHub releases.
  **Whoever picks up W12 should spend the first five minutes checking the latest Shizuku app
  release date and open-issue activity** before committing a week to the integration — if the
  project has gone dormant since this research, the calculus changes (a dead upstream that
  Android 17 breaks would strand the feature).

## What this settles for W12

- Feasibility: **yes on non-root** — the "becomes a footnote" branch of the work order is off
  the table.
- Mechanism: package **suspension** via `IPackageManager.setPackagesSuspended` through the
  Shizuku binder wrapper; `FORCE_STOP_PACKAGES` available to evict a running app at
  block-start.
- The friction shape D-012 wants is intact (reboot clears it; restart is manual), so the
  feature neither needs to be redesigned nor killed on that axis.
- Two things W12 must carry from here: loud degradation (fallback + indicator, already in the
  sketch), and the maintenance spot-check above before implementation starts.
