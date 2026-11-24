#!/bin/sh
# Run a focusfence-server admin command (codes, login-link) as the service user with the service's own environment,
# so it reads and writes the live store and prints links with the real public URL (audit S1). Needs sudo.
#   sudo server/deploy/ff-admin.sh admin login-link you@example.com
#   sudo server/deploy/ff-admin.sh admin codes list
exec systemd-run --quiet --pipe --wait --collect \
  --uid=ffbackend --gid=ffbackend \
  -p EnvironmentFile=/etc/ffbackend/env -p UMask=0077 \
  /opt/ffbackend/bin/focusfence-server "$@"
