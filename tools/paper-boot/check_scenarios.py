#!/usr/bin/env python3
"""Checks the in-plugin scenario lines in a Paper server log (PandiO/knk-workspace#421).

The plugin's `/knk scenario run all` writes (knk-core ScenarioReport; keep both in step):

  KNK-SCENARIO START <name>
  KNK-SCENARIO PASS <name> (<n> ticks)
  KNK-SCENARIO FAIL <name>: <reason>
  KNK-SCENARIO SUMMARY passed=<p> failed=<f> total=<t>

Passes when the summary line is there, no scenario failed, every started scenario has a PASS line, the summary's
counts match the lines, and at least --min-scenarios scenarios ran. Prints GitHub annotations and a result table,
appends that table to $GITHUB_STEP_SUMMARY when it is set, and exits 1 on failure.
"""
import argparse
import os
import re
import sys

START = re.compile(r"KNK-SCENARIO START (?P<name>[a-z0-9-]+)\s*$")
PASS = re.compile(r"KNK-SCENARIO PASS (?P<name>[a-z0-9-]+) \((?P<ticks>\d+) ticks\)\s*$")
FAIL = re.compile(r"KNK-SCENARIO FAIL (?P<name>[a-z0-9-]+): (?P<reason>.*?)\s*$")
SUMMARY = re.compile(r"KNK-SCENARIO SUMMARY passed=(?P<passed>\d+) failed=(?P<failed>\d+) total=(?P<total>\d+)\s*$")


def check(lines, min_scenarios=1):
    """Returns (errors, rows): the failure messages and one (name, result, detail) row per scenario."""
    started = []
    outcome = {}
    summary = None
    for line in lines:
        line = line.rstrip("\n")
        if match := START.search(line):
            started.append(match.group("name"))
        elif match := PASS.search(line):
            outcome[match.group("name")] = ("PASS", match.group("ticks") + " ticks")
        elif match := FAIL.search(line):
            outcome[match.group("name")] = ("FAIL", match.group("reason"))
        elif match := SUMMARY.search(line):
            summary = {key: int(value) for key, value in match.groupdict().items()}

    errors = []
    rows = []
    for name in started:
        result, detail = outcome.get(name, ("MISSING", "started but never finished"))
        rows.append((name, result, detail))
        if result == "FAIL":
            errors.append(f"scenario {name} failed: {detail}")
        elif result == "MISSING":
            errors.append(f"scenario {name} started but has no PASS or FAIL line")
    for name in outcome:
        if name not in started:
            errors.append(f"scenario {name} has a result but no START line")

    if summary is None:
        errors.append("no KNK-SCENARIO SUMMARY line: the scenario run did not finish (or never started)")
    else:
        passed = sum(1 for _, result, _ in rows if result == "PASS")
        failed = sum(1 for _, result, _ in rows if result == "FAIL")
        if (summary["passed"], summary["failed"], summary["total"]) != (passed, failed, len(rows)):
            errors.append(f"summary passed={summary['passed']} failed={summary['failed']} total={summary['total']} "
                          f"does not match the lines (passed={passed} failed={failed} total={len(rows)})")
    if len(started) < min_scenarios:
        errors.append(f"{len(started)} scenario(s) ran, expected at least {min_scenarios}")
    return errors, rows


def table(rows):
    out = ["| Scenario | Result | Detail |", "|---|---|---|"]
    for name, result, detail in rows:
        out.append(f"| `{name}` | {result} | {detail.replace('|', '/')} |")
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", help="the server's logs/latest.log")
    parser.add_argument("--min-scenarios", type=int, default=1, help="fail when fewer scenarios ran (default 1)")
    args = parser.parse_args(argv)

    with open(args.log, encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    errors, rows = check(lines, args.min_scenarios)
    print(table(rows))
    for error in errors:
        print(f"::error title=Plugin scenarios::{error}")
    verdict = "FAILED" if errors else "passed"
    print(f"Plugin scenario check: {verdict} ({len(rows)} scenario(s), {len(errors)} problem(s))")
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(f"### Plugin scenarios: {verdict}\n\n{table(rows)}\n\n")
            for error in errors:
                handle.write(f"1. {error}\n")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
