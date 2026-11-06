package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;

/**
 * Per-scenario rules a game mode may override on the lobby state machine (Siege Survival, PandiO/knk-workspace#41
 * link S5): the minimum player count, whether the match ends when its time runs out, and whether a drawn scenario
 * may start at all. {@link #PVP} is the ordinary Siege behaviour and the default of every machine, so nothing
 * changes for lobbies without a mode.
 */
public interface SiegeScenarioRules {

    /** Ordinary PvP Siege: the scenario's own minimum, timed, always startable. */
    SiegeScenarioRules PVP = new SiegeScenarioRules() { };

    /** The member count below which the draw and the start cancel with NOT_ENOUGH_PLAYERS. */
    default int playersMin(KnkSiegeScenario scenario) {
        return scenario.playersMin();
    }

    /** False: the running match never ends on TIME_EXPIRED and shows no final countdown. */
    default boolean timed(KnkSiegeScenario scenario) {
        return true;
    }

    /** False: the drawn scenario can't start (its mode configuration isn't ready) and the round cancels. */
    default boolean startable(KnkSiegeScenario scenario) {
        return true;
    }
}
