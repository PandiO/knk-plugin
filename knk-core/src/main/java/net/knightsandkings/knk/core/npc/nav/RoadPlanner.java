package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Plans the road part of a route on the K&K road graph for a subject (paper: the road router with the subject's
 * access policy, judged from where the subject stands). Needs no chunks (NAV-14). Called on the main thread; the
 * future completes on the main thread.
 */
public interface RoadPlanner {

    enum Status {
        /** {@link RoadPlan#line} is the road route. */
        FOUND,
        /** A route exists without the current rules: a rule block, it can clear (NAV-03). */
        BLOCKED,
        /** No route even without the rules. */
        NO_ROUTE,
        /** Start and goal are on road networks that do not connect: waiting never helps. */
        DIFFERENT_COMPONENTS,
        /** The world has no road network. */
        NO_NETWORK,
        /** The start or the goal is too far from any road. */
        OFF_ROAD,
        /** The router failed. */
        ERROR
    }

    /**
     * A gate door on the route.
     *
     * @param at the floor point where the road stretch with the gate ends: until the NPC is past it, the gate is
     *           ahead
     */
    record GateCrossing(int doorId, NavPoint at) {
        public GateCrossing {
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * @param line         the route as floor points (the road's polyline) for {@link Status#FOUND}; for
     *                     {@link Status#BLOCKED} the part up to the blocking element (may be empty); else empty
     * @param reason       why there is no route, for logs and the caller
     * @param blocking     what blocks ({@link Status#BLOCKED}), or null
     * @param gates        the gate doors {@code line} passes, in route order (Phase 3: a gate event replans only the
     *                     NPCs whose route crosses that gate)
     * @param stillBlocked for {@link Status#BLOCKED}: whether the blocking element still blocks a subject now,
     *                     asked on the main thread when an event may have opened it; null when unknown (then the
     *                     route is planned again to find out)
     */
    record RoadPlan(Status status, List<NavPoint> line, String reason, BlockingElement blocking,
                    List<GateCrossing> gates, Predicate<AccessSubject> stillBlocked) {
        public RoadPlan {
            Objects.requireNonNull(status, "status");
            line = line == null ? List.of() : List.copyOf(line);
            gates = gates == null ? List.of() : List.copyOf(gates);
        }

        public static RoadPlan found(List<NavPoint> line) {
            return new RoadPlan(Status.FOUND, line, null, null, List.of(), null);
        }

        public static RoadPlan found(List<NavPoint> line, List<GateCrossing> gates) {
            return new RoadPlan(Status.FOUND, line, null, null, gates, null);
        }

        public static RoadPlan blocked(List<NavPoint> partialLine, String reason, BlockingElement blocking,
                                       List<GateCrossing> gates, Predicate<AccessSubject> stillBlocked) {
            return new RoadPlan(Status.BLOCKED, partialLine, reason, blocking, gates, stillBlocked);
        }

        public static RoadPlan none(Status status, String reason) {
            return new RoadPlan(status, List.of(), reason, null, List.of(), null);
        }
    }

    /** Both points are floor points (the block stood on) in the same world. */
    CompletableFuture<RoadPlan> plan(AccessSubject subject, NavPoint fromFloor, NavPoint toFloor);
}
