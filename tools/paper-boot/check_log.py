#!/usr/bin/env python3
"""Checks a Paper server log from the CI boot test (PandiO/knk-workspace#420).

Passes when:
  1. the KnK version-stamp startup line (PandiO/knk-workspace#136) is there, with the expected commit when one is given;
  2. KnK logged "KnightsAndKings Plugin Enabled!" and WorldGuard and WorldEdit were enabled;
  3. the server finished starting ("Done (...)");
  4. no log entry from KnK, WorldGuard or WorldEdit is an ERROR or carries a stack trace, apart from the ones caused
     by the API being unreachable (the boot test runs without knk-web-api on purpose);
  5. with --expect-shutdown, KnK logged "KnightsAndKings Plugin Disabled!";
  6. with --expect-npc-navigation (PandiO/knk-workspace#543), Citizens was enabled and KnK's NPC navigation started on
     it ("[NPC] navigation: Citizens ..."), not "[NPC] navigation unavailable: <reason>".

An entry is a timestamped line plus the untimestamped lines after it (a stack trace). A trace printed with
printStackTrace() arrives one timestamped line per frame ("[..] [thread/WARN]: \tat ..."); such a trace joins the
latest WARN or ERROR of the same thread name and second that has no trace yet, also when other lines came in between (PandiO/knk-workspace#540: the plugin's join handling logs its "API
unreachable" error that way, and the scenarios' fake players join). It belongs to KnK, WorldGuard,
WorldEdit or Citizens when its text mentions them (logger name, plugin name or a stack frame). Prints GitHub
annotations and a summary; exits 1 on failure.

Citizens' own startup lines (its library downloads and relocations, "Loaded 2 templates.", "Loaded 0 NPCs.") are all
INFO on Paper 26.3 with Citizens build 4269, so no Citizens line is excused; one that turns into a WARN with a stack
trace or an ERROR fails the check like a KnK error does.
"""
import argparse
import re
import sys

ENTRY_START = re.compile(r"^\[(?P<time>\d{2}:\d{2}:\d{2})\] \[(?P<thread>[^\]]*)/(?P<level>[A-Z]+)\]: ?(?P<message>.*)$")
WATCHED = re.compile(r"knightsandkings|sk89q|worldguard|worldedit|citizens", re.IGNORECASE)
STACK_LINE = re.compile(r"^\s+at |^\s*Caused by: |^\s*\.\.\. \d+ more|^[\w.$]+(Exception|Error|Throwable)\b")
VERSION_STAMP = re.compile(r"\[KnightsAndKings\] KnightsAndKings (?P<version>\S+) \(commit (?P<commit>[^)]*)\)")
ENABLED = "[KnightsAndKings] KnightsAndKings Plugin Enabled!"
DISABLED = "[KnightsAndKings] KnightsAndKings Plugin Disabled!"
WORLDGUARD_ENABLED = re.compile(r"\[WorldGuard\] Enabling WorldGuard ")
WORLDEDIT_ENABLED = re.compile(r"\[WorldEdit\] Enabling WorldEdit ")
SERVER_DONE = re.compile(r"\]: Done \([\d.,]+s\)!")
CITIZENS_ENABLED = re.compile(r"\[Citizens\] Enabling Citizens ")
NPC_NAVIGATION_ON = re.compile(r"\[KnightsAndKings\] \[NPC\] navigation: Citizens ")
NPC_NAVIGATION_OFF = re.compile(r"\[KnightsAndKings\] \[NPC\] navigation unavailable: (?P<reason>.*)")


class Entry:
    def __init__(self, line_no, level, first_line, thread=None, time=None):
        self.line_no = line_no
        self.level = level
        self.thread = thread
        self.time = time
        self.lines = [first_line]
        self.inline_trace = False  # an untimestamped trace follows it (a logger's own exception)
        self.printed_trace = False  # printStackTrace() frames joined it

    @property
    def text(self):
        return "\n".join(self.lines)

    @property
    def has_stack_trace(self):
        return any(STACK_LINE.search(line) for line in self.lines[1:])


TRACE_HEADER = re.compile(r"^[\w.$]+(Exception|Error|Throwable)\b")


def parse_entries(lines):
    entries = []
    current = None
    recent = {}  # thread name -> its entries of the current second
    trace_target = {}  # thread name -> the entry its printStackTrace() frames are joining
    for number, raw in enumerate(lines, start=1):
        line = raw.rstrip("\n")
        match = ENTRY_START.match(line)
        if match and is_trace_frame(match):
            thread, message = match.group("thread"), match.group("message")
            target = trace_target.get(thread)
            if target is None or TRACE_HEADER.search(message):
                target = trace_owner(recent.get(thread, []), match.group("time"))
            if target is not None:
                target.lines.append(message)
                target.printed_trace = True
                trace_target[thread] = target
                continue
        if match:
            current = Entry(number, match.group("level"), line, match.group("thread"), match.group("time"))
            entries.append(current)
            same_second = [e for e in recent.get(current.thread, []) if e.time == current.time]
            recent[current.thread] = same_second + [current]
        elif current is not None:
            current.lines.append(line)
            if STACK_LINE.search(line):
                current.inline_trace = True
        else:  # text before the first timestamped line: keep it as its own entry
            current = Entry(number, "INFO", line)
            entries.append(current)
    return entries


def is_trace_frame(match):
    """Whether a timestamped line is a printStackTrace() frame: a stack line on System.err (logged as WARN or ERROR)."""
    return match.group("level") in ("WARN", "ERROR") and STACK_LINE.search(match.group("message")) is not None


def trace_owner(candidates, time):
    """The entry a new printStackTrace() trace belongs to: the thread's latest WARN or ERROR of the same second that has
    no trace of its own yet. Several pool threads share one name, so a logger's own exception (with its untimestamped
    trace) can come in between; it is skipped."""
    for entry in reversed(candidates):
        if entry.time == time and entry.level in ("WARN", "ERROR") and not entry.inline_trace \
                and not entry.printed_trace:
            return entry
    return None


def api_down_pattern(address):
    """Matches a failed connection to the unreachable API at host:port, whatever address the host resolved to.

    OkHttp writes 'Failed to connect to localhost/127.0.0.1:5294' or 'localhost/[0:0:0:0:0:0:0:1]:5294' for localhost:5294.
    """
    if not address:
        return None
    host, _, port = address.rpartition(":")
    return re.compile(r"Failed to connect to \S*" + re.escape(host) + r"\S*:" + re.escape(port) + r"\b")


def check(lines, expect_commit=None, unreachable_api=None, expect_shutdown=False, expect_npc_navigation=False):
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
    if expect_npc_navigation:
        if not CITIZENS_ENABLED.search(text):
            errors.append("Citizens was not enabled")
        if NPC_NAVIGATION_ON.search(text):
            notes.append("NPC navigation running on Citizens")
        else:
            off = NPC_NAVIGATION_OFF.search(text)
            errors.append("KnK's NPC navigation did not start on Citizens"
                          + (f": {off.group('reason').strip()}" if off else " ('[NPC] navigation: Citizens' not found)"))

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
    parser.add_argument("--expect-npc-navigation", action="store_true",
                        help="also require Citizens enabled and KnK's NPC navigation running on it")
    args = parser.parse_args(argv)

    with open(args.log, encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    errors, notes = check(lines, args.expect_commit, args.unreachable_api, args.expect_shutdown,
                          args.expect_npc_navigation)
    for note in notes:
        print(note)
    for error in errors:
        print(f"::error title=Paper boot test::{error}")
    print(f"Paper boot test log check: {'FAILED' if errors else 'passed'} ({len(errors)} problem(s))")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
