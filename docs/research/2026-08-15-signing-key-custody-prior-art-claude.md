# Q9 — Signing-key custody prior art (RethinkDNS, NetGuard, personalDNSfilter, AdAway)

**Engine:** Claude (Claude Code session with live web access — executed directly per `NEXT.md`
§"Research items", not via a browser deep-research session)
**Date:** 2026-08-15
**Feeds:** O-002 (`DECISIONS.md`), `docs/signing-key-custody.md`, unblocks W4 → W5
**Sourcing method:** primary sources only — repository `build.gradle` files, GitHub Actions
workflow YAML, F-Droid metadata and official F-Droid documentation. No blog posts or
best-practice advice were used as the basis of any claim about a specific project.

---

## Exact prompt used

```
I'm about to make a one-way-door decision for an open-source Android app distributed via
GitHub Releases (not yet on any app store): who generates and holds the release-signing
keystore, and whether GitHub Actions CI should sign releases automatically from a repo
secret, or releases should be signed by hand by a maintainer.

Context: it's a two-person project (one repo owner with admin access, one contributor).
Signing key identity is permanent — rotating it later forces every existing user to
uninstall and reinstall, losing local app data (there's no account/cloud sync).

How do comparable FOSS Android privacy/filtering tools handle this, specifically:
1. RethinkDNS
2. NetGuard
3. personalDNSfilter
4. AdAway

For each, if you can determine it from their public repos/docs/release process: is CI
(GitHub Actions or similar) trusted to hold the signing key as a secret and sign releases
automatically, or is signing done by hand by a maintainer on their own machine? Is there
any documented practice for backup/escrow of the keystore (e.g. splitting custody, keeping
an offline backup, using a hardware key)? Has any of these projects had to explain what
happens if the key-holder becomes unavailable?

This doesn't need to produce a single "right answer" — it needs to give two people
something concrete to react to instead of deciding from a blank page. Cite what you find
(repo files, CI workflow YAML, docs, issue discussions) rather than general best-practice
advice about signing keys in the abstract.
```

---

## Raw response

### Headline finding

**Zero of the four projects put their production signing key in CI.** All four sign the
artifact users actually install on a maintainer's own machine. The only project that lets CI
sign anything at all — RethinkDNS — does so with a **different key for a different
`applicationId`**, so a compromise of the CI-held key cannot produce an update to the real app.

That is a stronger and more specific result than "hand-signing is a defensible choice." It is
the unanimous practice of the comparison set, and the one apparent exception turns out, on
reading the build file, to reinforce the rule rather than break it.

### Summary table

| Project | Production key in CI? | How production is signed | Where credentials live |
|---|---|---|---|
| **RethinkDNS** | **No** | By hand, locally | `keystore.properties` in project root |
| **NetGuard** | **No** — no CI exists at all | By hand, locally | `keystore.properties` in project root |
| **AdAway** | **No** | By hand, locally | `~/.gradle/gradle.properties` (user-global, outside the repo) |
| **personalDNSfilter** | **No** — no CI exists at all | Outside Gradle entirely | No `signingConfigs` in the build at all |

---

### 1. RethinkDNS (`celzero/rethink-app`) — the only CI signing, and it is a separate key

RethinkDNS is the interesting case, because a shallow look ("their CI has keystore secrets")
gives exactly the wrong answer. Reading `app/build.gradle` shows **two distinct signing
configs**:

```groovy
signingConfigs {
    config {
        keyAlias keystoreProperties['keyAlias']
        keyPassword keystoreProperties['keyPassword']
        storeFile file(keystoreProperties['storeFile'])
        storePassword keystoreProperties['storePassword']
    }
    alpha {
        keyAlias System.getenv("ALPHA_KS_ALIAS")
        keyPassword System.getenv("ALPHA_KS_PASSPHRASE")
        storeFile file(String.valueOf(System.getenv("ALPHA_KS_FILE")))
        storePassword System.getenv("ALPHA_KS_STORE_PASSPHRASE")
    }
}
```

- `config` (production) reads from a **local `keystore.properties` file** — a machine-local
  file, never in CI.
- `alpha` reads **only from environment variables**, which is what CI supplies.

And critically, the alpha build type carries an `applicationIdSuffix`:

```groovy
alpha {
    applicationIdSuffix ".alpha"
    minifyEnabled true
    shrinkResources true
    signingConfig signingConfigs.alpha
```

**The alpha channel is a different Android app identity.** A leak of the CI-held alpha key
lets an attacker ship a fake `…​.alpha` build; it does **not** let them ship an update to
anyone running the production app. The blast radius is deliberately bounded by construction,
not by trust in GitHub's secret storage.

Source: `app/build.gradle` —
https://raw.githubusercontent.com/celzero/rethink-app/main/app/build.gradle

**Trigger gating.** The alpha keystore appears only in `nightly.yml`, whose triggers are:

```yaml
on:
  workflow_dispatch: # to be able to run it manually
  schedule:
    - cron: '53 21 27 * *' # on 27 every month
```

`pull_request` is **not** among them — so the exfiltration path that
`docs/signing-key-custody.md` §4 correctly identifies ("anyone who can push a workflow change
to a branch that runs on `pull_request`") is closed by construction.

The keystore is not stored base64-encoded but **GPG-encrypted**, decrypted at build time:

```
echo "${{ secrets.ALPHA_KS_ASC }}" > alphaKeystore.jks.asc
gpg -d --passphrase "${{ secrets.ALPHA_KS_PASSPHRASE }}" --batch alphaKeystore.jks.asc > app/rdnsAlpha.jks
```

Secrets used: `ALPHA_KS_ASC`, `ALPHA_KS_PASSPHRASE`, `ALPHA_KS_STORE_PASSPHRASE`.

Source: `.github/workflows/nightly.yml` —
https://raw.githubusercontent.com/celzero/rethink-app/main/.github/workflows/nightly.yml

By contrast their main CI workflow `android.yml` (triggered on `push` and `pull_request` to
`main`) contains **no signing whatsoever** — it runs `./gradlew lint` and
`assembleWebsiteFullDebug` / `assembleFdroidFullDebug` only. The workflow that touches key
material and the workflow that runs on untrusted PRs are strictly disjoint.

Source: https://raw.githubusercontent.com/celzero/rethink-app/main/.github/workflows/android.yml

### 2. NetGuard (`M66B/NetGuard`) — no CI at all

`https://api.github.com/repos/M66B/NetGuard/contents/.github/workflows` returns **HTTP 404** —
the directory does not exist. NetGuard has no GitHub Actions pipeline, so the question of CI
signing does not arise: everything is built and signed on the maintainer's machine.

`app/build.gradle`:

```groovy
signingConfigs {
    release {
        storeFile file(keystoreProperties['storeFile'])
        storePassword keystoreProperties['storePassword']
        keyAlias keystoreProperties['keyAlias']
        keyPassword keystoreProperties['keyPassword']
    }
}
```

…with `signingConfig signingConfigs.release` on the release build type, credentials loaded from
a `keystore.properties` file in the project root.

Source: https://raw.githubusercontent.com/M66B/NetGuard/master/app/build.gradle

**NetGuard is also the one project that has had to explain the consequence to users
in writing.** FAQ #15, verbatim:

> "F-Droid builds are not supported because I have no control over if and when the F-Droid
> version of NetGuard will be updated, so I cannot guarantee timely updates, for example if
> there is a critical or security issue.
>
> Because F-Droid builds and GitHub releases are signed differently, an F-Droid build needs to
> be uninstalled first to be able to update to a GitHub release."

Source: https://raw.githubusercontent.com/M66B/NetGuard/master/FAQ.md

This is the exact failure mode `docs/signing-key-custody.md` describes under "What makes it
one-way" — observed in production, on a comparable app, stated by its maintainer as a standing
caveat users must live with. It is not a hypothetical.

### 3. AdAway (`AdAway/AdAway`) — CI builds, maintainer signs

AdAway *does* have CI (`.github/workflows/android-ci.yml`), and it *does* build a release APK —
but it builds it **unsigned** and never touches key material:

- Triggers on `push` to `master` only.
- Runs unit tests, then `./gradlew assembleRelease --stacktrace`.
- No keystore handling, no signing secrets, no base64/GPG decode, no `apksigner`/`jarsigner`.
- The APK upload step is **commented out**:
  ```yaml
  # - name: Upload APK
  #   uses: actions/upload-artifact@v3
  ```
- Remaining steps are dependency-graph submission and SonarCloud analysis.

Source: https://raw.githubusercontent.com/AdAway/AdAway/master/.github/workflows/android-ci.yml

Its `app/build.gradle` signing config is notable for **where** it reads credentials from:

```groovy
signingConfigs {
    release {
        storeFile file(signingStoreLocation)
        storePassword signingStorePassword
        keyAlias signingKeyAlias
        keyPassword signingKeyPassword
    }
}
```

…with `buildTypes.release.signingConfig = signingConfigs.release`, and the four properties
supplied from **`~/.gradle/gradle.properties`** — the user-global Gradle properties file in the
maintainer's home directory, *not* a file inside the project tree.

Source: https://api.github.com/repos/AdAway/AdAway/contents/app/build.gradle

This is a meaningfully safer variant than RethinkDNS's and NetGuard's project-root
`keystore.properties`: a file that lives outside the working tree cannot be accidentally
committed, cannot be caught by an over-broad `git add -A`, and cannot be swept up by anything
that syncs or backs up the project directory.

### 4. personalDNSfilter (`IngoZenz/personaldnsfilter`) — signing happens outside the build

- No `.github` directory at the repository root → no CI of any kind.
- `app/build.gradle` contains **no `signingConfigs` block at all**, and the release build type
  references no signing config (it carries only `minifyEnabled` and `proguardFiles`).

Sources:
- https://api.github.com/repos/IngoZenz/personaldnsfilter/contents/
- https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/master/app/build.gradle

Signing is therefore done entirely outside Gradle — by hand with `apksigner`, or via Android
Studio's "Generate Signed Bundle / APK" dialog, both of which keep the keystore off the build
configuration entirely.

*Confidence note:* this is verified for `app/build.gradle` and the repository root. The repo
also contains a `full_package/` directory that was not exhaustively read; the conclusion "no CI"
is solid (no `.github` exists), the conclusion "no signing config in the app module" is solid,
but a signing config elsewhere in the tree was not ruled out exhaustively.

---

### Backup, escrow and key-holder unavailability — a negative finding

**None of the four projects documents a keystore backup, escrow, or succession practice
anywhere public.** No split custody, no hardware-token signing, no "if I disappear" plan, no
documented offline-backup procedure. Searches for such practice across FOSS Android projects
returned only generic best-practice advice (never commit keys, back them up, keep them secure)
rather than any project's actual stated policy.

This should be read carefully rather than as permission to skip the question. It means:

- There is **no established convention to copy** here. `docs/signing-key-custody.md` §3 is
  ahead of the comparison set simply by having written the question down.
- The absence is not evidence the problem is unreal — it is evidence that single-maintainer
  FOSS projects generally carry an undocumented bus factor of 1, which is a known and
  frequently-realised failure mode for abandoned Android apps, not a solved one.

The closest thing to an authoritative statement is F-Droid's own signing documentation, which
says signing keys "need to remain secure for the entire lifetime of an F-Droid repo or Android
app, and must be kept safe, secure and well backed up" — a requirement, not a procedure.

Source: https://f-droid.org/docs/Signing_Process/

---

### The F-Droid dimension — relevant to O-002 even though we are not on F-Droid yet

This was not in the prompt but is load-bearing for the decision, because O-005 / research Q5
contemplate F-Droid inclusion later, and F-Droid inclusion **changes who holds a signing key**.

By default, F-Droid builds from source and signs with **its own** per-app generated key:

> "By default, the 'publish' server will generate and manage a signing key for each individual
> app."

> "All apps are signed by the key devoted to that app unless the upstream specifically requests
> multiple apps be signed by the same key."

Source: https://f-droid.org/docs/Security_Model/

Reproducible builds are the escape hatch — they let F-Droid ship the *developer's* signed APK
after verifying a byte-identical rebuild:

> "F-Droid ships APKs that are signed by the upstream developer(s). Which proves to you that the
> developer confirmed: 'this is what I intended to publish, this was built from my code'."

> "Whenever a reproducible build is established at F-Droid, the hash of the corresponding
> developer's certificate used to sign their APKs is stored along with the other metadata on
> F-Droid's end. The keyword for that in the Build Metadata is `AllowedAPKSigningKeys`."

Sources:
- https://f-droid.org/2023/09/03/reproducible-builds-signing-keys-and-binary-repos.html
- https://f-droid.org/docs/Reproducible_Builds/

**Concrete data point:** AdAway's F-Droid metadata (`metadata/org.adaway.yml` in `fdroiddata`)
contains **neither** an `AllowedAPKSigningKeys` field **nor** a `Binaries` field — meaning
F-Droid signs AdAway with F-Droid's own key, and an AdAway user on F-Droid cannot cross-update
to a maintainer-signed AdAway APK without uninstalling.

Source: https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.adaway.yml

So the practical consequence for Focus Fence: if it ever ships on F-Droid **without**
reproducible builds, there will be two Focus Fence identities in the world, and users cannot
migrate between them without losing every rule they have set — the same thing NetGuard FAQ #15
warns about. Setting up reproducible builds from the first release is much cheaper than
retrofitting it after users exist on both channels.

---

### What this does and does not settle

**Does not settle:** O-002 itself. Questions 1–3 in `docs/signing-key-custody.md` (who holds it,
does the second maintainer hold a copy, where the backup lives) get **no** guidance from prior
art, because none of the four projects documents an answer.

**Does settle, as strongly as prior art can:** question 4, "does CI sign or do you sign by
hand?" Four out of four hand-sign the production artifact. The doc's existing recommendation
("sign by hand for the first release") is not merely defensible — it is what every comparable
project in the set actually does.

**Adds a third option the doc did not consider:** RethinkDNS's split-key pattern. If CI
convenience is wanted later, the move is not "give CI the production key with careful trigger
gating" but "give CI a *different* key for a *different* `applicationId`." That converts a
catastrophic-if-leaked secret into a bounded one, and it composes with hand-signing rather than
replacing it.

### New questions surfaced

1. **Reproducible builds as a first-release concern, not an F-Droid-submission concern.** Worth
   deciding before the first signed APK exists rather than after. Relates to Q5 / O-005.
2. **Is `keystore.properties` the right credential location for this project?** AdAway's
   `~/.gradle/gradle.properties` is strictly safer, and this repo lives under a `OneDrive` path
   — a project-root credentials file here is a sync target, which is precisely the risk
   `docs/signing-key-custody.md` §3 already warns about for the keystore itself.
