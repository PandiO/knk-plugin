package net.knightsandkings.knk.core.scenario;

/**
 * The platform side of a scenario run: prepares a fresh scene before each scenario and tears it down
 * afterwards (knk-paper: an empty, force-loaded patch of a world that is cleared again at the end).
 *
 * @param <S> the scene type
 */
public interface ScenarioHost<S> {

    /**
     * Prepares the scene for one scenario.
     *
     * @param scenario the scenario about to run
     * @param index    its position in this suite run, from 0 (lets the host give each one its own area)
     */
    S open(Scenario<S> scenario, int index) throws Exception;

    /** Tears the scene down again; called once per opened scene, also after a failure. */
    void close(S scene) throws Exception;
}
