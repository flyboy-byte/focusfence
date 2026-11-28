#!/usr/bin/env python3
"""Fail when a public doc still describes a removed feature (plan 15, item 2).

    tools/check_docs.py          # exit 1 and print file:line for each hit

Plan 13 replaced the partner system and four public docs kept describing the old one for two days. This keeps a list
of removed things, each with the plan that removed it, and scans only the docs people read to learn what the app does
today. Dated history (AGENTS.md's status log, DECISIONS.md, CHANGELOG.md, docs/plans/, docs/specs/) is not scanned.

A line that mentions a removed thing on purpose (say, to explain why old apps are refused) ends with the marker
<!-- docs: past -->, which renders as nothing in Markdown and HTML.

Removing a feature: add its words here in the same PR, and fix every doc this then flags.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# (pattern, what it was, the plan that removed it). Patterns are case-insensitive unless they start with (?-i).
REMOVED = [
    (r"mailbox", "pairing mailboxes", "plan 13"),
    (r"(?-i:\bSMS\b)", "SMS partner alerts", "plan 13"),
    (r"pairing key", "pairing keys", "plan 13"),
    (r"(?<![\w.])/i/", "keyed /i/ invite links", "plan 13"),
    (r"X-FF-Invite", "the X-FF-Invite header", "plan 13"),
    (r"double opt-in", "email double opt-in", "plan 13"),
    (r"GroupSync", "plan 12's GroupSync", "plan 13"),
    (r"unlock code", "partner unlock codes", "plan 13"),
]

PUBLIC = ["README.md", "STATE.md", "SECURITY.md", "CONTRIBUTING.md", "CLAUDE.md", "server/README.md", "docs/site/*.html"]

MARKER = "<!-- docs: past -->"


def files():
    for pattern in PUBLIC:
        yield from sorted(ROOT.glob(pattern))


def main():
    rules = [(re.compile(p if p.startswith("(?-i") else f"(?i){p}"), what, plan) for p, what, plan in REMOVED]
    hits = 0
    for path in files():
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if MARKER in line:
                continue
            for rx, what, plan in rules:
                m = rx.search(line)
                if m:
                    hits += 1
                    print(f"{path.relative_to(ROOT)}:{n}: \"{m.group(0)}\" ({what}, removed in {plan})")
    if hits:
        print(f"\n{hits} mention(s) of removed features. Update the doc, or end the line with {MARKER} "
              "if it describes the past on purpose.")
        return 1
    print("docs: no removed features named")
    return 0


if __name__ == "__main__":
    sys.exit(main())
