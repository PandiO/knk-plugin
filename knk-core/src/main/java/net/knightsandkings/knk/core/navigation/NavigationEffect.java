package net.knightsandkings.knk.core.navigation;

import net.knightsandkings.knk.core.roads.route.BlockedExplainer;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.Maneuver;
import net.knightsandkings.knk.core.roads.route.Route;

import java.util.List;
import java.util.Optional;

/**
 * What {@link NavigationSession} asks the Paper runtime to do (plan 2d: "effects returned, not
 * executed", mirroring {@code core/siege/SiegeEffect}). Phase 4's {@code NavigationService}
 * applies them in list order on the main thread with an exhaustive {@code switch}. Effects carry
 * the facts (and the verdict texts the core already has); message wording is the runtime's.
 */
public sealed interface NavigationEffect {

    /** Why a route computation is requested. */
    enum RouteReason {
        /** The session just started. */
        INITIAL,
        /** The player left the route (DESIGN §6.4 re-route rule). */
        OFF_ROUTE,
        /** An element on the route became blocked ("The West Gate closed — recalculating"). */
        ELEMENT_BLOCKED,
        /** Something opened; the new route is taken only when clearly shorter (DESIGN §6.7). */
        IMPROVEMENT
    }

    /** Why a session ended. */
    enum EndReason {
        /** {@code /navigate stop}. */
        STOPPED,
        /** No route (no road connects, too far, nothing even ignoring availability). */
        NO_ROUTE,
        /** Start and goal are in different network components. */
        DIFFERENT_COMPONENTS,
        /** {@code max-session-minutes} elapsed. */
        TIMEOUT,
        QUIT,
        DEATH,
        WORLD_CHANGE,
        /** Teleported more than 16 blocks (DESIGN §6.4). */
        TELEPORT,
        /** Joined a siege lobby (plan D2). */
        SIEGE,
        /** The destination is no longer navigable (e.g. the network was rebuilt away). */
        DESTINATION_LOST,
        /** The player is already at the destination / inside the region. */
        ALREADY_THERE
    }

    /**
     * Compute a route (off the main thread) and feed the result back through
     * {@link NavigationSession#onRouteResult}. With {@code keepCurrentUnlessShorter} the session
     * keeps guiding on its current route meanwhile and adopts the new one only when it is shorter
     * by the configured margin.
     */
    record ComputeRouteEffect(RouteReason reason, boolean keepCurrentUnlessShorter) implements NavigationEffect {}

    /**
     * A route was adopted: draw it, refresh the HUD, announce the maneuvers. {@code explanation} is
     * present when the route is a partial one ("No open route to X — the West Gate is closed.
     * Guiding you to the gate."); {@code reason} says why it was (re)computed.
     */
    record RouteAdoptedEffect(Route route, List<Maneuver> maneuvers, RouteReason reason,
                              Optional<BlockedExplainer.Explanation> explanation) implements NavigationEffect {
        public RouteAdoptedEffect {
            maneuvers = List.copyOf(maneuvers);
        }
    }

    /**
     * A re-route was started because {@code verdict}'s element blocked the active route
     * (DESIGN §6.7 live changes); the runtime tells the player.
     */
    record RerouteStartedEffect(RouteReason reason, Optional<EdgeVerdict> verdict) implements NavigationEffect {}

    /**
     * An improvement re-route found nothing better (or the same route); nothing changes. Emitted
     * so the runtime can log; no player message expected.
     */
    record RouteKeptEffect() implements NavigationEffect {}

    /**
     * Per tick while guiding: where the player is on the route and what to show.
     *
     * @param along          polyline distance travelled (the projection)
     * @param offRoute       3D distance from the route
     * @param remainingBlocks walked length still to go
     * @param etaSeconds     at sprint speed
     * @param progress       0..1 for the boss bar
     * @param aheadPoint     the trail point ~6 blocks ahead, for the action-bar arrow
     * @param nextManeuver   the next instruction ahead of the player, if any
     * @param metersToNext   distance to it (0 when none)
     */
    record GuidanceEffect(double along, double offRoute, double remainingBlocks, double etaSeconds, double progress,
                          double[] aheadPoint, Optional<Maneuver> nextManeuver, double metersToNext)
        implements NavigationEffect {}

    /** Within {@code arrive-distance} of the goal: sound + "You have arrived at …". */
    record ArrivedEffect() implements NavigationEffect {}

    /** The session is over; clear trail and HUD, fire {@code NavigationEndEvent}. */
    record EndedEffect(EndReason reason) implements NavigationEffect {}
}
