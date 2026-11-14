"""Tests for check_scenarios.py (run: python3 -m unittest discover -s tools/paper-boot -p 'test_*.py')."""
import unittest

from check_scenarios import check

PREFIX = "[20:01:02] [Server thread/INFO]: [KnightsAndKings] "


def log(*lines):
    return [PREFIX + line + "\n" for line in lines]


class CheckScenariosTest(unittest.TestCase):

    def test_all_passed(self):
        errors, rows = check(log(
            "KNK-SCENARIO START gate-open-close",
            "KNK-SCENARIO PASS gate-open-close (83 ticks)",
            "KNK-SCENARIO START global-region-explosion",
            "KNK-SCENARIO PASS global-region-explosion (31 ticks)",
            "KNK-SCENARIO SUMMARY passed=2 failed=0 total=2",
        ), min_scenarios=2)
        self.assertEqual([], errors)
        self.assertEqual([("gate-open-close", "PASS", "83 ticks"), ("global-region-explosion", "PASS", "31 ticks")], rows)

    def test_a_failed_scenario_fails(self):
        errors, _ = check(log(
            "KNK-SCENARIO START gate-open-close",
            "KNK-SCENARIO FAIL gate-open-close: timed out after 100 ticks waiting for: the door is OPEN",
            "KNK-SCENARIO SUMMARY passed=0 failed=1 total=1",
        ))
        self.assertEqual(["scenario gate-open-close failed: timed out after 100 ticks waiting for: the door is OPEN"],
                         errors)

    def test_a_missing_summary_fails(self):
        errors, _ = check(log(
            "KNK-SCENARIO START gate-open-close",
            "KNK-SCENARIO PASS gate-open-close (83 ticks)",
        ))
        self.assertTrue(any("no KNK-SCENARIO SUMMARY" in error for error in errors))

    def test_a_scenario_without_a_result_fails(self):
        errors, rows = check(log(
            "KNK-SCENARIO START gate-open-close",
            "KNK-SCENARIO SUMMARY passed=0 failed=0 total=0",
        ))
        self.assertIn("scenario gate-open-close started but has no PASS or FAIL line", errors)
        self.assertEqual("MISSING", rows[0][1])

    def test_a_summary_that_does_not_match_fails(self):
        errors, _ = check(log(
            "KNK-SCENARIO START a",
            "KNK-SCENARIO PASS a (1 ticks)",
            "KNK-SCENARIO SUMMARY passed=2 failed=0 total=2",
        ))
        self.assertTrue(any("does not match the lines" in error for error in errors))

    def test_too_few_scenarios_fail(self):
        errors, _ = check(log("KNK-SCENARIO SUMMARY passed=0 failed=0 total=0"), min_scenarios=1)
        self.assertIn("0 scenario(s) ran, expected at least 1", errors)

    def test_no_scenario_lines_at_all_fail(self):
        errors, _ = check(["[20:01:02] [Server thread/INFO]: Done (3.2s)!\n"])
        self.assertTrue(any("no KNK-SCENARIO SUMMARY" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
