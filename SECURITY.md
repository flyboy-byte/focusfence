# Security

Focus Fence AC handles sensitive things: what someone browses, protection that's meant to be hard to switch off, and
partner reports that whoever runs the server must not be able to read (decision D-023). Reports about any of that are
very welcome.

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private reporting: the repo's **Security** tab →
**Report a vulnerability**. Include what you found, how to reproduce it, and what it lets someone do.

You'll get an answer within a week. Fixes land as normal pull requests once a fix is ready, and you're credited unless
you'd rather not be.

## In scope

- The Android app (`app/`): bypassing the filter or strict mode without it being logged, leaking activity off the
  phone, the accessibility service and uninstall protection.
- The server (`server/`): reading a sealed report or someone else's data, sending email or SMS as the server, the
  admin sign-in, the join and partner-link flows.
- The partner report page (`server/src/main/resources/static/report.js`) and its key handling.

## Out of scope

- The known limits in the README (DNS-level blocking can be bypassed by apps with their own encrypted DNS; the owner
  can always turn things off, and partners are told).
- An operator who modifies their own server's code: D-023 says plainly that it does not defend against that.
