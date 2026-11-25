#!/bin/sh
# Install or upgrade the Focus Fence server under systemd, from an unpacked release tarball.
#
#   v=1.0.0; f=focusfence-server-$v.tar.gz
#   curl -fLO https://github.com/flyboy-byte/focusfence/releases/download/server-v$v/$f
#   curl -fLO https://github.com/flyboy-byte/focusfence/releases/download/server-v$v/$f.sha256
#   sha256sum -c $f.sha256 && tar xzf $f && cd focusfence-server-$v
#   sudo ./install.sh              # first time: sets up, then asks you to edit the env file
#   sudo ./install.sh              # later versions: upgrade, health check, automatic rollback
#   sudo ./install.sh --rollback   # go back to the previous version by hand
#
# FF_SERVICE=name picks the unit, user and paths (/opt/name, /etc/name/env, /var/lib/name). Default focusfence-server.
# Needs a JRE 17+ on the host (e.g. apt install openjdk-17-jre-headless).
set -eu

NAME=${FF_SERVICE:-focusfence-server}
PREFIX=/opt/$NAME
CONF=/etc/$NAME
UNIT=/etc/systemd/system/$NAME.service
HERE=$(cd "$(dirname "$0")" && pwd)

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root (sudo)"
command -v systemctl >/dev/null || die "systemd not found; use Docker Compose instead (README)"

port() {
    p=$(sed -n 's/^FF_PORT=//p' "$CONF/env" 2>/dev/null | tail -n 1 | tr -d '"'\''[:space:]')
    echo "${p:-8080}"
}

healthy() {
    url="http://127.0.0.1:$(port)/healthz"
    i=0
    while [ $i -lt 30 ]; do
        if command -v curl >/dev/null; then
            body=$(curl -fsS -m 3 "$url" 2>/dev/null || true)
        elif command -v wget >/dev/null; then
            body=$(wget -qO- -T 3 "$url" 2>/dev/null || true)
        else
            say "No curl or wget: skipping the health check. Check $url yourself."
            return 0
        fi
        [ "$body" = ok ] && return 0
        i=$((i + 1)); sleep 2
    done
    return 1
}

swap_back() {
    [ -d "$PREFIX.prev" ] || die "no previous version in $PREFIX.prev"
    rm -rf "$PREFIX.failed"
    mv "$PREFIX" "$PREFIX.failed"
    mv "$PREFIX.prev" "$PREFIX"
    systemctl restart "$NAME"
}

if [ "${1:-}" = --rollback ]; then
    swap_back
    healthy && say "Rolled back. Running $(cat "$PREFIX/VERSION" 2>/dev/null || echo 'the previous version')." \
        || die "rolled back, but the health check still fails: journalctl -u $NAME -n 50"
    exit 0
fi

[ -x "$HERE/bin/focusfence-server" ] || die "run this from an unpacked release (bin/focusfence-server missing)"
command -v java >/dev/null || [ -n "${JAVA_HOME:-}" ] || die "no Java found: install a JRE 17 or newer"

first=no
[ -f "$UNIT" ] || first=yes

if [ $first = yes ]; then
    nologin=$(command -v nologin || echo /usr/sbin/nologin)
    id "$NAME" >/dev/null 2>&1 || useradd --system --user-group --no-create-home --shell "$nologin" "$NAME"
    install -d -m 755 "$CONF"
    if [ ! -f "$CONF/env" ]; then
        install -m 600 "$HERE/.env.example" "$CONF/env"
        # Compose sets these itself; under systemd the server binds to loopback behind your TLS proxy.
        printf '\n# Address and port the server listens on; point your TLS proxy here.\nFF_HOST=127.0.0.1\nFF_PORT=8080\n' >> "$CONF/env"
    fi
    sed "s/@NAME@/$NAME/g" "$HERE/systemd/focusfence-server.service" > "$UNIT"
    systemctl daemon-reload
fi

# Stage next to the live copy, then swap, so a failed copy never leaves a half-installed server.
rm -rf "$PREFIX.new"
mkdir -p "$PREFIX.new"
cp -R "$HERE/bin" "$HERE/lib" "$PREFIX.new/"
[ -f "$HERE/VERSION" ] && cp "$HERE/VERSION" "$PREFIX.new/"
chown -R root:root "$PREFIX.new"
chmod -R go-w "$PREFIX.new"
if [ -d "$PREFIX" ]; then
    rm -rf "$PREFIX.prev"
    mv "$PREFIX" "$PREFIX.prev"
fi
mv "$PREFIX.new" "$PREFIX"

if [ $first = yes ]; then
    systemctl enable "$NAME" >/dev/null 2>&1
    say "Installed $NAME."
    say "Next: edit $CONF/env (at least FF_PUBLIC_URL, RESEND_API_KEY, FF_MAIL_FROM), then"
    say "  sudo systemctl start $NAME && curl http://127.0.0.1:$(port)/healthz"
    say "and put HTTPS in front (nginx.conf.example in this folder)."
    exit 0
fi

systemctl restart "$NAME"
if healthy; then
    say "Upgraded $NAME to $(cat "$PREFIX/VERSION" 2>/dev/null || echo 'this version'). Previous copy kept in $PREFIX.prev."
else
    say "Health check failed after the upgrade; rolling back."
    swap_back
    healthy || die "rollback also unhealthy: journalctl -u $NAME -n 50"
    die "kept the previous version. The failed one is in $PREFIX.failed; logs: journalctl -u $NAME -n 50"
fi
