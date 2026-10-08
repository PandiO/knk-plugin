package net.knightsandkings.knk.core.navigation;

import net.knightsandkings.knk.core.navigation.NavigationEffect.ArrivedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.BlockedEndReachedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.ComputeRouteEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.GuidanceEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RerouteStartedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteAdoptedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteKeptEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteReason;
import net.knightsandkings.knk.core.roads.route.BlockedExplainer;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.Maneuver;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One player's navigation, as a pure state machine (plan 2d, DESIGN §6.4, §6.7):
 * {@code PLANNING → GUIDING ⇄ REROUTING → ARRIVED | ENDED(reason)}. Inputs are the route results,
 * position ticks, availability notices and stop requests; outputs are {@link NavigationEffect}s
 * the Paper runtime applies (draw, HUD, messages, route computations). Nothing here touches a
 * thread or a clock: the caller passes the current tick.
 *
 * <ul>
 *   <li><b>Off-route:</b> more than {@code rerouteDistance} from the route for
 *       {@code rerouteAfterTicks} → a re-route, at most once per {@code rerouteMinIntervalTicks}.
 *       While the re-route runs the old route keeps being drawn.</li>
 *   <li><b>Element blocked</b> (gate closed on the route, domain changed): immediate re-route with
 *       the reason; a re-route that finds only a partial route (BLOCKED) still adopts it.</li>
 *   <li><b>Element opened:</b> at most once per {@code improvementIntervalTicks} a route is
 *       recomputed and adopted only if shorter than {@code (1 - threshold) × remaining}.</li>
 *   <li><b>Arrival:</b> within {@code arriveDistance} (3D) of a full route's end point. The end of a
 *       partial route ("Guiding you to the gate") is no arrival: {@link BlockedEndReachedEffect} once,
 *       and the session keeps guiding until the element opens (live test 2026-10-08, N5). While the
 *       route is partial, an improvement is taken only when it is a full route ({@code REOPENED}).
 *       Whether the player is inside the destination region is the runtime's check → {@link #end}.</li>
 *   <li><b>Timeout:</b> {@code maxSessionMinutes} after the start.</li>
 * </ul>
 * Positions are the player's <b>feet</b> block coordinates; the session compares them with the
 * route's floor-block polyline at {@code y + 1} (Phase 2c decision 1).
 */
public final class NavigationSession {

    public enum State {
        PLANNING,
        GUIDING,
        REROUTING,
        ARRIVED,
        ENDED
    }

    private final SessionParameters parameters;
    private final Function<Route, List<Maneuver>> maneuvers;
    private final long startedTick;

    private State state = State.PLANNING;
    private boolean started;
    private Route route;
    private List<Maneuver> routeManeuvers = List.of();
    private Optional<BlockedExplainer.Explanation> explanation = Optional.empty();
    private double along;
    private int offRouteTicks;
    private long lastRerouteRequestTick = Long.MIN_VALUE;
    private long lastImprovementRequestTick = Long.MIN_VALUE;
    private RouteReason pendingReason;
    private boolean pendingKeepUnlessShorter;
    private EndReason endReason;
    private boolean blockedEndAnnounced;

    /**
     * @param parameters tunables
     * @param maneuvers  how to derive instructions from a route ({@code ManeuverBuilder::build})
     * @param nowTick    the current tick; the session asks for its first route immediately - read
     *                   the effects of {@link #start()}
     */
    public NavigationSession(SessionParameters parameters, Function<Route, List<Maneuver>> maneuvers, long nowTick) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.maneuvers = Objects.requireNonNull(maneuvers, "maneuvers");
        this.startedTick = nowTick;
    }

    // ==================== Queries ====================

    public State state() {
        return state;
    }

    public boolean isActive() {
        return state == State.GUIDING || state == State.REROUTING || state == State.PLANNING;
    }

    /** The route being guided along (empty while planning or after a failed start). */
    public Optional<Route> route() {
        return Optional.ofNullable(route);
    }

    public List<Maneuver> maneuvers() {
        return routeManeuvers;
    }

    /** Present when the current route is a partial one. */
    public Optional<BlockedExplainer.Explanation> explanation() {
        return explanation;
    }

    /** Polyline distance travelled along the current route. */
    public double along() {
        return along;
    }

    public Optional<EndReason> endReason() {
        return Optional.ofNullable(endReason);
    }

    public long startedTick() {
        return startedTick;
    }

    // ==================== Commands ====================

    /** PLANNING: request the first route. */
    public List<NavigationEffect> start() {
        if (state != State.PLANNING || started) {
            return List.of();
        }
        started = true;
        return List.of(requestRoute(RouteReason.INITIAL, false, startedTick));
    }

    /**
     * A requested route computation finished (any thread computed it, the runtime calls this on
     * the main thread).
     */
    public List<NavigationEffect> onRouteResult(RouteResult result, long nowTick) {
        if (state == State.ENDED || state == State.ARRIVED) {
            return List.of();
        }
        RouteReason reason = pendingReason == null ? RouteReason.INITIAL : pendingReason;
        boolean keepUnlessShorter = pendingKeepUnlessShorter;
        pendingReason = null;
        pendingKeepUnlessShorter = false;
        switch (result.status()) {
            case FOUND, BLOCKED -> {
                Route candidate = result.route();
                boolean wasPartial = route != null && explanation.isPresent();
                if (reason == RouteReason.IMPROVEMENT && route != null && result.status() == RouteResult.Status.BLOCKED) {
                    // still blocked: keep the partial route (its end and its message) instead of a new one
                    state = State.GUIDING;
                    return List.of(new RouteKeptEffect());
                }
                if (keepUnlessShorter && route != null) {
                    // an improvement must be a full route, clearly shorter than what is left
                    boolean partial = result.status() == RouteResult.Status.BLOCKED;
                    if (partial || candidate.length() >= (1 - parameters.improvementThreshold()) * remainingBlocks()) {
                        state = State.GUIDING;
                        return List.of(new RouteKeptEffect());
                    }
                }
                adopt(candidate, result.explanationOptional());
                state = State.GUIDING;
                List<NavigationEffect> effects = new ArrayList<>();
                RouteReason announced = reason == RouteReason.IMPROVEMENT && wasPartial && explanation.isEmpty()
                    ? RouteReason.REOPENED : reason;
                effects.add(new RouteAdoptedEffect(route, routeManeuvers, announced, explanation));
                if (isAtEnd(route.start().x(), route.start().y(), route.start().z())) {
                    // already there (an empty route, or a start within arrive distance of the goal)
                    effects.addAll(atEnd());
                }
                return effects;
            }
            case DIFFERENT_COMPONENTS -> {
                if (route != null && keepUnlessShorter) {
                    state = State.GUIDING;
                    return List.of(new RouteKeptEffect());
                }
                return end(EndReason.DIFFERENT_COMPONENTS);
            }
            case NO_ROUTE -> {
                if (route != null && keepUnlessShorter) {
                    state = State.GUIDING;
                    return List.of(new RouteKeptEffect());
                }
                return end(EndReason.NO_ROUTE);
            }
            default -> throw new IllegalStateException("unhandled " + result.status());
        }
    }

    /**
     * The player's position this tick (feet block coordinates). Produces the guidance effect and
     * handles arrival, off-route re-routes and the session timeout.
     */
    public List<NavigationEffect> tick(double feetX, double feetY, double feetZ, long nowTick) {
        if (state != State.GUIDING && state != State.REROUTING) {
            return List.of();
        }
        if (nowTick - startedTick >= parameters.maxSessionTicks()) {
            return end(EndReason.TIMEOUT);
        }
        if (route == null) {
            return List.of();
        }
        double floorY = feetY - 1;
        Route.Projection projection = route.project(feetX, floorY, feetZ,
            along - parameters.offRouteLookBackBlocks());
        along = Math.max(along, projection.along());
        List<NavigationEffect> effects = new ArrayList<>();
        if (isAtEnd(feetX, floorY, feetZ)) {
            if (explanation.isEmpty()) {
                return atEnd();
            }
            effects.addAll(atEnd()); // the closed gate / the domain's edge: wait there
        }
        if (projection.distance() > parameters.rerouteDistance()) {
            offRouteTicks++;
        } else {
            offRouteTicks = 0;
        }
        if (state == State.GUIDING && offRouteTicks >= parameters.rerouteAfterTicks()
            && (lastRerouteRequestTick == Long.MIN_VALUE
                || nowTick - lastRerouteRequestTick >= parameters.rerouteMinIntervalTicks())) {
            offRouteTicks = 0;
            effects.add(new RerouteStartedEffect(RouteReason.OFF_ROUTE, Optional.empty()));
            effects.add(requestRoute(RouteReason.OFF_ROUTE, false, nowTick));
        }
        effects.add(guidance(projection));
        return effects;
    }

    /**
     * The runtime found that an element of the active route is now blocked (gate closed, domain
     * changed - DESIGN §6.7 live changes, D13 re-check): re-route now with the reason.
     */
    public List<NavigationEffect> onElementBlocked(EdgeVerdict verdict, long nowTick) {
        if (state != State.GUIDING || route == null) {
            return List.of();
        }
        lastRerouteRequestTick = nowTick;
        return List.of(new RerouteStartedEffect(RouteReason.ELEMENT_BLOCKED, Optional.of(verdict)),
            requestRoute(RouteReason.ELEMENT_BLOCKED, false, nowTick));
    }

    /**
     * A gate or domain opened somewhere: ask for a fresh route, keep the current one unless the new
     * one is clearly shorter. Rate-limited to once per {@code improvementIntervalTicks}; also used
     * when the current route is a partial one (the blocking element may have opened).
     */
    public List<NavigationEffect> onElementOpened(long nowTick) {
        if (state != State.GUIDING || route == null) {
            return List.of();
        }
        if (lastImprovementRequestTick != Long.MIN_VALUE
            && nowTick - lastImprovementRequestTick < parameters.improvementIntervalTicks()) {
            return List.of();
        }
        lastImprovementRequestTick = nowTick;
        boolean partial = explanation.isPresent();
        return List.of(requestRoute(RouteReason.IMPROVEMENT, !partial, nowTick));
    }

    /** End the session for a runtime reason (stop command, quit, death, teleport, siege, …). */
    public List<NavigationEffect> end(EndReason reason) {
        if (state == State.ENDED) {
            return List.of();
        }
        state = State.ENDED;
        endReason = reason;
        return List.of(new EndedEffect(reason));
    }

    // ==================== Internals ====================

    private ComputeRouteEffect requestRoute(RouteReason reason, boolean keepUnlessShorter, long nowTick) {
        state = route == null ? State.PLANNING : State.REROUTING;
        pendingReason = reason;
        pendingKeepUnlessShorter = keepUnlessShorter;
        if (reason == RouteReason.OFF_ROUTE || reason == RouteReason.ELEMENT_BLOCKED) {
            lastRerouteRequestTick = nowTick; // the "max once per 3 s" rule is about re-routes
        }
        return new ComputeRouteEffect(reason, keepUnlessShorter);
    }

    /** At the route's end: arrived, or (a partial route) the blocked end announced once. */
    private List<NavigationEffect> atEnd() {
        if (explanation.isEmpty()) {
            state = State.ARRIVED;
            return List.of(new ArrivedEffect());
        }
        if (blockedEndAnnounced) {
            return List.of();
        }
        blockedEndAnnounced = true;
        return List.of(new BlockedEndReachedEffect(explanation.get()));
    }

    private void adopt(Route candidate, Optional<BlockedExplainer.Explanation> why) {
        this.blockedEndAnnounced = false;
        this.route = candidate;
        this.routeManeuvers = List.copyOf(maneuvers.apply(candidate));
        this.explanation = why;
        this.along = 0;
        this.offRouteTicks = 0;
    }

    private boolean isAtEnd(double x, double floorY, double z) {
        double[] end = route.end().point();
        double dx = end[0] - x, dy = end[1] - floorY, dz = end[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= parameters.arriveDistance();
    }

    /** Walked length still ahead, scaled from the polyline progress. */
    public double remainingBlocks() {
        if (route == null) {
            return 0;
        }
        double total = route.polylineLength();
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, (1 - along / total) * route.length());
    }

    private GuidanceEffect guidance(Route.Projection projection) {
        double remaining = remainingBlocks();
        double total = route.polylineLength();
        double progress = total <= 0 ? 1 : Math.min(1, along / total);
        double[] ahead = route.pointAt(along + 6);
        Optional<Maneuver> next = Optional.empty();
        double toNext = 0;
        for (Maneuver m : routeManeuvers) {
            if (m.along() >= along - 0.5) {
                next = Optional.of(m);
                toNext = Math.max(0, m.along() - along);
                break;
            }
        }
        return new GuidanceEffect(along, projection.distance(), remaining, remaining / parameters.sprintSpeed(),
            progress, ahead, next, toNext);
    }
}
