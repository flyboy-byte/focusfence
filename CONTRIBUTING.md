# Contributing

Thanks for looking. Focus Fence AC is small and run by two people, so a few habits keep things easy.

## Before you start

- Read [`STATE.md`](STATE.md) for where things stand and [`DECISIONS.md`](DECISIONS.md) for why. Some things that look
  like gaps are on purpose: the filter only ever sees DNS, partners never get a clear screenshot, and nothing is ever
  enforced against the phone's owner (they can always turn things off; partners are told).
- For anything bigger than a bug fix, open an issue first so we can say whether it fits. There are forms for bug
  reports, feature requests and feedback; the app's Settings → Feedback & bug reports fills in versions.
- The repository is public: never paste logs, log exports or screenshots into an issue or PR.
- Security problems go through private reporting, not issues: see [`SECURITY.md`](SECURITY.md).

## Building

JDK 17 or 21 and the Android SDK (platform 34).

```sh
./gradlew testDebugUnitTest assembleDebug lint   # app
./gradlew -p server test                         # server
```

There is no emulator test suite. Decision logic lives in `app/src/main/java/app/focusfence/dns/`, free of Android
types, so it can have JVM tests: put new logic there and test it. The server compiles that folder too.

**Changing what the app and server send each other** (a path, a JSON field, a signature format) means raising
`dns/Protocol.LEVEL` and adding a compatibility suite for the new level under `server/src/test/.../compat/`. The old
suites must keep passing: app and server support each other two levels back (D-041).

## Pull requests

- One change per PR, branched from `main`. We squash-merge.
- **The title is what users read.** It becomes a line in the changelog and in the app's "What's new", so write it
  plainly for someone using the app, with a type in front: `feat: block notices for gambling sites`,
  `fix(server): …`, `docs: …`.
- The PR template's sections are optional; fill in the ones that help. Say how you tested it: on a phone, or tests
  only.
- Don't bump the version number. Releases are their own small PR.

## Licence

Focus Fence is AGPL-3.0 (since 2.22; D-038). By sending a pull request you agree your change is published under it.

AI agents working in this repo: read [`AGENTS.md`](AGENTS.md) and [`CLAUDE.md`](CLAUDE.md) first.
