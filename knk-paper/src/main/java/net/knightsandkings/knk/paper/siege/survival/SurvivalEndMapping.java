package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;

import java.util.OptionalInt;

/**
 * How a survival ending is carried through the Siege lifecycle until link S8 adds survival results (DESIGN §3 step 7,
 * §3a; S62). Bukkit-free.
 *
 * <ul>
 *   <li>The Siege reason the lobby's match ends with: every survival loss (team wipe, insta-lose objective, all
 *       objectives captured) is {@link SiegeEndReason#TEAM_ELIMINATED}; "no players left" too (the PvP membership
 *       path produces it); admin abort / restart keep their Siege abort reasons.</li>
 *   <li>The result: a survival loss names the NPC alliance as the winner ({@code LAST_ALLIANCE_STANDING}, or
 *       {@code INSTANT_VICTORY_CAPTURE} for an insta-lose capture), so the PvP result pipeline records a lost match
 *       (participation rewards as in Siege, no win reward); no players left is a draw; aborts stay aborted.</li>
 * </ul>
 */
public final class SurvivalEndMapping {

    private SurvivalEndMapping() {
    }

    /** The Siege end reason a survival ending is announced with to the lobby state machine. */
    public static SiegeEndReason siegeReason(SurvivalEndReason reason) {
        return switch (reason) {
            case TEAM_WIPE, INSTA_LOSE_OBJECTIVE_CAPTURED, ALL_OBJECTIVES_CAPTURED, NO_PLAYERS_LEFT -> SiegeEndReason.TEAM_ELIMINATED;
            case ADMIN_ABORT -> SiegeEndReason.ADMIN_STOPPED;
            case SERVER_RESTART -> SiegeEndReason.SERVER_RESTART;
            case TECHNICAL_LIMIT -> SiegeEndReason.API_OUTAGE;
        };
    }

    /** The survival reason a Siege ending the runtime did not trigger itself means (an abort, a membership end). */
    public static SurvivalEndReason survivalReason(SiegeEndReason reason, boolean playersLeft) {
        return switch (reason) {
            case ADMIN_STOPPED -> SurvivalEndReason.ADMIN_ABORT;
            case SERVER_RESTART -> SurvivalEndReason.SERVER_RESTART;
            case API_OUTAGE -> SurvivalEndReason.TECHNICAL_LIMIT;
            case INSTANT_VICTORY -> SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED;
            case TEAM_ELIMINATED, NOT_ENOUGH_PLAYERS, TIME_EXPIRED -> playersLeft ? SurvivalEndReason.TEAM_WIPE : SurvivalEndReason.NO_PLAYERS_LEFT;
        };
    }

    /** The match result for a survival ending (see the class comment). */
    public static WinResolver.Result result(SurvivalEndReason reason, SiegeEndReason siegeReason, int npcAllianceGroup) {
        if (reason.isAbort() && reason != SurvivalEndReason.NO_PLAYERS_LEFT) {
            return new WinResolver.Result(siegeReason, OptionalInt.empty(), WinResolver.Decision.ABORTED);
        }
        return switch (reason) {
            case NO_PLAYERS_LEFT -> new WinResolver.Result(siegeReason, OptionalInt.empty(), WinResolver.Decision.DRAW);
            case INSTA_LOSE_OBJECTIVE_CAPTURED -> new WinResolver.Result(siegeReason, OptionalInt.of(npcAllianceGroup),
                WinResolver.Decision.INSTANT_VICTORY_CAPTURE);
            default -> new WinResolver.Result(siegeReason, OptionalInt.of(npcAllianceGroup), WinResolver.Decision.LAST_ALLIANCE_STANDING);
        };
    }
}
