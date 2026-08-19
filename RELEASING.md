# Releasing Focus Fence

> **Audience:** whoever holds the signing key (@sidewinderzz, per O-002 in `DECISIONS.md`).
> **Use when:** cutting a real release. Every command below runs on your own machine — the key
> never touches GitHub, by design.

## Why this is a manual process

O-002 decided releases are **hand-signed**, not signed by CI — the key never lives in a GitHub
Actions secret. That trades a manual step per release for a materially smaller leak surface. See
`docs/signing-key-custody.md` for the full reasoning.

## One-time setup (only before your first release)

If you haven't generated the keystore yet, read `docs/signing-key-custody.md` in full first — it
covers where the key and its backup must physically live, and why not inside this repo's working
tree (even gitignored).

```sh
keytool -genkeypair -v -keystore focusfence-release.jks -keyalg RSA -keysize 4096 \
  -validity 10950 -alias focusfence
```

Then create `keystore.properties` at the repo root (gitignored — see `keystore.properties.example`
for the shape) pointing at the keystore's real path and its passwords. Record the certificate
fingerprint somewhere durable and **outside this repo**:

```sh
keytool -list -v -keystore focusfence-release.jks -alias focusfence
```

## Cutting a release

1. **Update `CHANGELOG.md`** with a new version section, then commit it on your branch and merge
   to `main` through the normal flow.
2. **Tag it** and push the tag:
   ```sh
   git tag v2.2.0
   git push origin v2.2.0
   ```
3. **CI drafts a release automatically** (`.github/workflows/release.yml`): it runs the tests,
   builds an *unsigned* release APK, and creates a **draft** GitHub Release with that unsigned APK
   and `mapping.txt` attached, labelled clearly as a placeholder. If the tests fail, no draft is
   created — check the Actions run.
4. **Build and sign locally**, with `keystore.properties` present:
   ```sh
   ./gradlew assembleRelease
   ```
   With `keystore.properties` in place this produces `app-release.apk` (signed), not
   `app-release-unsigned.apk`.
5. **Verify the signature:**
   ```sh
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
   Confirm the certificate fingerprint matches the one you recorded at setup.
6. **Compute the SHA-256:**
   ```sh
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```
7. **Finish the draft release on GitHub:**
   - Remove the unsigned placeholder APK CI attached.
   - Upload the real signed `app-release.apk` in its place.
   - Replace the `mapping.txt` with the one from this same local build (they must match the
     binary you're shipping).
   - Edit the release body: delete the "DRAFT — UNSIGNED" warning, and add the SHA-256 from step 6.
   - Publish the release (draft → published).
8. **Install it on a real device** and confirm it launches, as a last sanity check before calling
   it done.

## If something goes wrong

- **Wrong file uploaded / need to redo a release:** delete the GitHub Release and the tag, fix the
  problem, re-tag, re-push. Never reuse a tag name for different bits.
- **Lost the keystore:** unrecoverable, per O-002. The app must be republished under a new
  identity; there is no update path for existing installs. This is exactly the scenario the backup
  requirement in `docs/signing-key-custody.md` exists to prevent.
