# Bundled fonts

The UI uses **IBM Plex Sans** for prose and **IBM Plex Mono** for anything numeric or
label-like — the Home counter, countdowns, rule tags, and the all-caps section headers.
Mono is what keeps figures column-aligned while they tick.

## Why bundled and not downloaded

Android's downloadable-fonts provider fetches from Google Play Services at runtime. That
would put a network call on app start in an app whose entire premise is that nothing leaves
the phone, so it is not an option here. The six faces ship in `app/src/main/res/font/`.

## Provenance

From the official IBM Plex releases, `fonts/complete/ttf/` inside each archive:

- Sans — <https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-sans%401.1.0/ibm-plex-sans.zip>
- Mono — <https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-mono%401.1.0/ibm-plex-mono.zip>

Files are the upstream TTFs byte for byte, renamed only to satisfy the `res/font` naming
rules (lowercase, no hyphens):

| In the repo | Upstream file | SHA-256 |
| --- | --- | --- |
| `plex_sans_regular.ttf` | `IBMPlexSans-Regular.ttf` | `975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5` |
| `plex_sans_medium.ttf` | `IBMPlexSans-Medium.ttf` | `331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907` |
| `plex_sans_semibold.ttf` | `IBMPlexSans-SemiBold.ttf` | `a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1` |
| `plex_mono_regular.ttf` | `IBMPlexMono-Regular.ttf` | `fe11304a5fe956d5744e9b6a246cc83d90425245e75a62230044966ca96a7f50` |
| `plex_mono_medium.ttf` | `IBMPlexMono-Medium.ttf` | `0bede3debdea8488bbb927f8f0650d915073209734a67fe8cd5a3320b572511c` |
| `plex_mono_semibold.ttf` | `IBMPlexMono-SemiBold.ttf` | `c9417148ce13f8fa7d2d5c9180bbc141f72aa0d814ffeb280f6904dc2b1bbd7a` |

Verify with `sha256sum app/src/main/res/font/*.ttf`.

These are the full upstream faces, not subsets — a subset would be smaller but could not be
checked against an upstream hash, which matters more here than the size. Six faces are
~1.1 MB on disk and compress to roughly half that in the APK.

## License

IBM Plex is licensed under the SIL Open Font License 1.1, which permits bundling in an
application. Upstream license text: <https://github.com/IBM/plex/blob/master/LICENSE.txt>.

## Using them

Weights are referenced as concrete files (`@font/plex_sans_semibold`), not through a
`<font-family>` with `fontWeight` entries. On API 26–27 a `TextView` only selects a non-400
weight out of a family via `textStyle`, so a family would silently render every 500/600
style at regular weight on the oldest supported devices. The type scale in
`res/values/type.xml` is the only place that names a font file; layouts use its styles.
