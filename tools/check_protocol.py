#!/usr/bin/env python3
"""D-041: fail an app release that the newest server release can't serve.

    tools/check_protocol.py            # compares HEAD's app with the newest server-v* tag

Reads Protocol.LEVEL and WINDOW from app/src/main/java/app/focusfence/dns/Protocol.kt at HEAD (the app being
released) and at the newest `server-v*` tag (what self-hosters install). A server release without that file predates
D-041 and speaks level 1. Same rule as Protocol.check: the server must be no older than the app's oldest supported
level, and the app no older than the server's. Reads git only.
"""
import re
import subprocess
import sys

PATH = "app/src/main/java/app/focusfence/dns/Protocol.kt"


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def levels(ref):
    r = git("show", f"{ref}:{PATH}")
    if r.returncode != 0:
        return 1, 2  # before D-041
    level = int(re.search(r"const val LEVEL = (\d+)", r.stdout).group(1))
    window = int(re.search(r"const val WINDOW = (\d+)", r.stdout).group(1))
    return level, window


tags = [t for t in git("tag", "--list", "server-v*", "--sort=-v:refname").stdout.split() if t]
if not tags:
    print("no server-v* release yet; nothing to check")
    sys.exit(0)
server_tag = tags[0]
app_level, app_window = levels("HEAD")
server_level, server_window = levels(server_tag)
app_oldest = max(1, app_level - app_window)
server_oldest = max(1, server_level - server_window)
print(f"app protocol {app_level} (serves back to {app_oldest}); {server_tag} protocol {server_level} (serves back to {server_oldest})")
if server_level < app_oldest:
    sys.exit(f"{server_tag} is too old for this app. Tag a new server-v release first (AGENTS.md → Releases).")
if app_level < server_oldest:
    sys.exit(f"this app is too old for {server_tag}: it dropped protocol {app_level}.")
print("ok: inside each other's window")
