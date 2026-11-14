package net.knightsandkings.knk.core.scenario;

/**
 * Thrown by a step to fail its scenario with a readable reason (no stack trace in the report).
 * Any other exception fails the scenario too, reported with its class name.
 */
public final class ScenarioAssertionError extends RuntimeException {

    public ScenarioAssertionError(String reason) {
        super(reason, null, false, false);
    }
}
