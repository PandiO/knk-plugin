package net.knightsandkings.knk.core.scenario;

import java.util.List;

/**
 * The machine-readable lines CI reads from the server log (PandiO/knk-workspace#421). The formats are
 * a contract with knk-plugin {@code tools/paper-boot/check_scenarios.py}; change both together.
 * <pre>
 * KNK-SCENARIO START gate-open-close
 * KNK-SCENARIO PASS gate-open-close (83 ticks)
 * KNK-SCENARIO FAIL gate-open-close: timed out after 100 ticks waiting for: door open
 * KNK-SCENARIO SUMMARY passed=2 failed=1 total=3
 * </pre>
 * Every line starts with {@link #PREFIX}; a reason is flattened to one line.
 */
public final class ScenarioReport {

    public static final String PREFIX = "KNK-SCENARIO";

    private ScenarioReport() {
    }

    public static String startLine(String name) {
        return PREFIX + " START " + name;
    }

    public static String line(ScenarioResult result) {
        if (result.passed()) {
            return PREFIX + " PASS " + result.name() + " (" + result.ticks() + " ticks)";
        }
        return PREFIX + " FAIL " + result.name() + ": " + oneLine(result.reason());
    }

    public static String summaryLine(List<ScenarioResult> results) {
        long passed = results.stream().filter(ScenarioResult::passed).count();
        long failed = results.size() - passed;
        return PREFIX + " SUMMARY passed=" + passed + " failed=" + failed + " total=" + results.size();
    }

    /** Collapses line breaks and runs of whitespace, so a reason never spills onto a second log line. */
    static String oneLine(String reason) {
        if (reason == null || reason.isBlank()) {
            return "(no reason given)";
        }
        return reason.replaceAll("\\s+", " ").trim();
    }

    /** A readable reason for an exception thrown by a step. */
    static String describe(Throwable error) {
        if (error instanceof ScenarioAssertionError) {
            return error.getMessage();
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
