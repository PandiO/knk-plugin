#!/usr/bin/env python3
"""Checks a Paper server log from the CI boot test (PandiO/knk-workspace#420).

Passes when:
  1. the KnK version-stamp startup line (PandiO/knk-workspace#136) is there, with the expected commit when one is given;
  2. KnK logged "KnightsAndKings Plugin Enabled!" and WorldGuard and WorldEdit were enabled;
  3. the server finished starting ("Done (...)");
  4. no log entry from KnK, WorldGuard or WorldEdit is an ERROR or carries a stack trace, apart from the ones caused
     by the API being unreachable (the boot test runs without knk-web-api on purpose);
  5. with --expect-shutdown, KnK logged "KnightsAndKings Plugin Disabled!".

An entry is a timestamped line plus the untimestamped lines after it (a stack trace). It belongs to KnK, WorldGuard or
WorldEdit when its text mentions them (logger name, plugin name or a stack frame). Prints GitHub annotations and a
summary; exits 1 on failure.
"""
import argparse
import re
import sys

ENTRY_START = re.compile(r"^\[\d{2}:\d{2}:\d{2}\] \[(?P<thread>[^\]]*)/(?P<level>[A-Z]+)\]: ?(?P<message>.*)$")
WATCHED = re.compile(r"knightsandkings|sk89q|worldguard|worldedit", re.IGNORECASE)
STACK_LINE = re.compile(r"^\s+at |^\s*Caused by: |^\s*\.\.\. \d+ more|^[\w.$]+(Exception|Error|Throwable)\b")
VERSION_STAMP = re.compile(r"\[KnightsAndKings\] KnightsAndKings (?P<version>\S+) \(commit (?P<commit>[^)]*)\)")
ENABLED = "[KnightsAndKings] KnightsAndKings Plugin Enabled!"
DISABLED = "[KnightsAndKings] KnightsAndKings Plugin Disabled!"
WORLDGUARD_ENABLED = re.compile(r"\[WorldGuard\] Enabling WorldGuard ")
WORLDEDIT_ENABLED = re.compile(r"\[WorldEdit\] Enabling WorldEdit ")
SERVER_DONE = re.compile(r"\]: Done \([\d.,]+s\)!")


class Entry:
    def __init__(self, line_no, level, first_line):
        self.line_no = line_no
        self.level = level
        self.lines = [first_line]

    @property
    def text(self):
        return "\n".join(self.lines)

    @property
    def has_stack_trace(self):
        return any(STACK_LINE.search(line) for line in self.lines[1:])


def parse_entries(lines):
    entries = []
    current = None
    for number, raw in enumerate(lines, start=1):
        line = raw.rstrip("\n")
        match = ENTRY_START.match(line)
        if match:
            current = Entry(number, match.group("level"), line)
            entries.append(current)
        elif current is not None:
            current.lines.append(line)
        else:  # text before the first timestamped line: keep it as its own entry
            current = Entry(number, "INFO", line)
            entries.append(current)
    return entries


def api_down_pattern(address):
    """Matches a failed connection to the unreachable API at host:port, whatever address the host resolved to.

    OkHttp writes 'Failed to connect to localhost/127.0.0.1:5294' or 'localhost/[0:0:0:0:0:0:0:1]:5294' for localhost:5294.
    """
    if not address:
        return None
    host, _, port = address.rpartition(":")
    return re.compile(r"Failed to connect to \S*" + re.escape(host) + r"\S*:" + re.escape(port) + r"\b")


def check(lines, expect_commit=None, unreachable_api=None, expect_shutdown=False):
    """Returns (errors, notes): the failure messages and the informational lines."""
    errors = []
    notes = []
    entries = parse_entries(lines)
    text = "\n".join(line.rstrip("\n") for line in lines)

    stamp = VERSION_STAMP.search(text)
    if not stamp:
        errors.append("KnK version-stamp startup line not found (the plugin did not start)")
    else:
        notes.append(f"KnK version {stamp.group('version')}, commit {stamp.group('commit')}")
        if expect_commit and stamp.group("commit") != expect_commit:
            errors.append(f"KnK reports commit {stamp.group('commit')}, expected {expect_commit}")
    if ENABLED not in text:
        errors.append(f"'{ENABLED}' not found (KnK did not finish enabling)")
    if not WORLDGUARD_ENABLED.search(text):
        errors.append("WorldGuard was not enabled")
    if not WORLDEDIT_ENABLED.search(text):
        errors.append("WorldEdit was not enabled")
    if not SERVER_DONE.search(text):
        errors.append("The server did not finish starting ('Done (...)!' not found)")
    if expect_shutdown and DISABLED not in text:
        errors.append(f"'{DISABLED}' not found (KnK did not shut down cleanly)")

    api_down = api_down_pattern(unreachable_api)
    excused = 0
    for entry in entries:
        if entry.level != "ERROR" and not entry.has_stack_trace:
            continue
        if not WATCHED.search(entry.text):
            continue
        if api_down and api_down.search(entry.text):
            excused += 1
            continue
        kind = "ERROR" if entry.level == "ERROR" else f"{entry.level} with a stack trace"
        errors.append(f"line {entry.line_no}: {kind}: {entry.lines[0][:300]}")
    if api_down:
        notes.append(f"{excused} error/stack-trace entries excused: caused by the unreachable API at {unreachable_api}")
    return errors, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", help="the server's logs/latest.log")
    parser.add_argument("--expect-commit", help="commit the version stamp must report")
    parser.add_argument("--unreachable-api", help="host:port of the API the test leaves unreachable, e.g. localhost:5294")
    parser.add_argument("--expect-shutdown", action="store_true", help="also require KnK's 'Plugin Disabled!' line")
    args = parser.parse_args(argv)

    with open(args.log, encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    errors, notes = check(lines, args.expect_commit, args.unreachable_api, args.expect_shutdown)
    for note in notes:
        print(note)
    for error in errors:
        print(f"::error title=Paper boot test::{error}")
    print(f"Paper boot test log check: {'FAILED' if errors else 'passed'} ({len(errors)} problem(s))")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
