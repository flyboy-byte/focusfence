# Draft message to @sidewinderzz (not sent; Logan decides the channel and wording)

_Drafted 2026-11-23 at Logan's request ("message sidewinderzz about making ours main"). Facts from `docs/plans/05-group-and-release.md` section 7._

> Hey, I've taken Focus Fence further on the `claude/logging-and-accountability-plan` branch and I think it should become `main`. I'd like your call on it.
>
> **What it adds:** a working accountability loop (partner reports, alerts, unlock codes, up to 4-phone groups), a self-hostable server (Docker Compose, independently audited three times, live for me since 2026-11-23), on-device screenshot scoring with a tuned flag policy, a measured battery history, 220+ app and 35 server tests, and signed releases. Every layer is optional: with them off it is still the light on-device blocker.
> **What yours has that mine lacks:** multi-source lists with provenance, an allowlist, the UI redesign, your release signing and draft-release workflow, the benchmark script. I'd bring those over by feature.
> **What only you can decide:** whether to merge at all; the landing path (the two branches have 25 conflicting files, so I'd propose making mine the base and porting yours); signing custody (O-002); the licence; branch protection; GitHub Pages for a privacy page; and a D-017 recording the decision (draft in the repo).
>
> Nothing has been touched on `main` or `shareddev`, and no PR is open. Happy to walk you through it on a call first.
