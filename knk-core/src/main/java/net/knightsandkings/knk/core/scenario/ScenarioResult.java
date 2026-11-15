package net.knightsandkings.knk.core.scenario;

/**
 * The outcome of one scenario.
 *
 * @param name   the scenario's name
 * @param passed whether every step and cleanup passed
 * @param reason the failure reason, null when passed
 * @param ticks  server ticks the scenario took
 */
public record ScenarioResult(String name, boolean passed, String reason, long ticks) {

    public static ScenarioResult pass(String name, long ticks) {
        return new ScenarioResult(name, true, null, ticks);
    }

    public static ScenarioResult fail(String name, String reason, long ticks) {
        return new ScenarioResult(name, false, reason, ticks);
    }
}
