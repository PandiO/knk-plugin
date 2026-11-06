package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeScenarioRules;
import net.knightsandkings.knk.core.siege.WinResolver;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The few places where a game mode on top of the Siege lifecycle (Siege Survival, PandiO/knk-workspace#41 link S5)
 * changes what {@link SiegeService} does for a lobby. Every method has the ordinary PvP answer as its default and
 * {@link #NONE} is the default implementation, so a lobby without a mode behaves exactly as before.
 */
public interface SiegeModeHooks {

    /** Ordinary PvP Siege for every lobby. */
    SiegeModeHooks NONE = new SiegeModeHooks() { };

    /** The scenario rules of a lobby's state machine (min players, timed, startable). */
    default SiegeScenarioRules scenarioRules(int lobbyId) {
        return SiegeScenarioRules.PVP;
    }

    /** Present: every human member is put on this team at the split, at the start and on an admin join. */
    default OptionalInt singleTeam(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) {
        return OptionalInt.empty();
    }

    /**
     * True: a leave or quit during the match does not run the PvP membership check (team eliminated / not enough
     * players); the mode ends the match itself when its own conditions say so.
     */
    default boolean suppressMembershipEnd(SiegeLobbyRuntime lobby, SiegeMatch match) {
        return false;
    }

    /** False: a disconnect is a plain leave (no rejoin slot is held). */
    default boolean allowRejoin(SiegeLobbyRuntime lobby) {
        return true;
    }

    /** Present: the result of the match instead of {@link WinResolver#resolve}. */
    default Optional<WinResolver.Result> resolveResult(SiegeLobbyRuntime lobby, SiegeMatch match, SiegeEndReason reason) {
        return Optional.empty();
    }

    /** The capture configuration a match of this lobby plays with (side-capture reduction and the like). */
    default KnkSiegeConfiguration matchConfiguration(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario,
                                                     KnkSiegeConfiguration configuration) {
        return configuration;
    }
}
