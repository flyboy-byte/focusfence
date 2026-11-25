#!/bin/sh
# Run a focusfence-server admin command (codes, login-link) as the service user with the service's own environment,
# so it reads and writes the live store and prints links with the real public URL (audit S1). Needs sudo.
# For the systemd install (install.sh); FF_SERVICE picks the name, as there. With Docker Compose use instead:
#   docker compose exec server /opt/focusfence-server/bin/focusfence-server admin codes list
#   sudo ./ff-admin.sh admin login-link you@example.com
#   sudo ./ff-admin.sh admin codes list
NAME=${FF_SERVICE:-focusfence-server}
exec systemd-run --quiet --pipe --wait --collect \
  --uid="$NAME" --gid="$NAME" \
  -p EnvironmentFile="/etc/$NAME/env" -p UMask=0077 \
  -p Environment="FF_DATA_DIR=/var/lib/$NAME" -p WorkingDirectory="/var/lib/$NAME" \
  "/opt/$NAME/bin/focusfence-server" "$@"
