package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over a {@link RoadNetworkSnapshot} (DESIGN §6.2 step 4, plan 2d). Edge cost =
 * {@code length × classCost × profile.costMultiplier × edge.costMultiplier}; the heuristic is the
 * Euclidean distance to the nearest goal times the network's cheapest cost factor (admissible and
 * consistent because every walked length is at least its chord). The start and the goals are
 * <b>virtual nodes</b> splitting their edges (negative state ids); a start and a goal on the same
 * edge connect directly along it. {@code Oneway} edges are walked From → To only. Edges the
 * request's {@link AccessPolicy} blocks are skipped; PASS_THROUGH edges are used and their hint is
 * kept on the {@link Route.Step}. Start and goal in different components are refused before
 * searching (DESIGN §5.8). Binary heap ({@link PriorityQueue}); ties on {@code f} prefer the larger
 * {@code g} (the entry nearer the goal), then insertion order - deterministic.
 *
 * <p>Pure and thread-safe: a router holds only the immutable snapshot; every search allocates its
 * own state. Per-request work is a few thousand map operations for the design's ~15 k nodes.
 */
public final class AStarRouter {

    private static final int START_STATE = -1;
    /**
     * A start this close (blocks) to an end of a blocked start edge is at that node, and leaves from it (rev. 7
     * Part A: the snapper may put a player on the split node before a door onto the door's piece).
     */
    static final double AT_NODE = 0.5;

    private final RoadNetworkSnapshot snapshot;

    public AStarRouter(RoadNetworkSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public RoadNetworkSnapshot snapshot() {
        return snapshot;
    }

    /**
     * {@link RouteResult.Status#FOUND}, {@link RouteResult.Status#DIFFERENT_COMPONENTS} or
     * {@link RouteResult.Status#NO_ROUTE} - never BLOCKED; see {@link #routeOrExplain}.
     */
    public RouteResult route(RouteRequest request) {
        Search search = new Search(request);
        if (search.reachableGoals.isEmpty()) {
            return RouteResult.differentComponents();
        }
        Route route = search.run();
        return route == null ? RouteResult.noRoute() : RouteResult.found(route);
    }

    /**
     * Like {@link #route}, and when no usable route exists asks the {@link BlockedExplainer} why:
     * {@link RouteResult.Status#BLOCKED} with the partial route, or NO_ROUTE when even an
     * all-open search fails.
     */
    /**
     * With the request's real policy: the route to the reached point nearest to a goal, when no goal can
     * be reached (the explainer's "as close as the open roads go", N14); a goal's route if one is reachable
     * after all; empty when nothing gets closer than the start.
     */
    public Optional<Route> routeTowards(RouteRequest request) {
        Search search = new Search(request);
        if (search.reachableGoals.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(search.runTowardsClosest());
    }

    public RouteResult routeOrExplain(RouteRequest request) {
        RouteResult result = route(request);
        if (result.status() != RouteResult.Status.NO_ROUTE) {
            return result;
        }
        return new BlockedExplainer(this).explain(request).map(RouteResult::blocked).orElse(result);
    }

    // ---- the search -----------------------------------------------------------------------------

    private record Entry(double f, double g, long seq, int state) {
    }

    private static final class Arrival {
        final int fromState;
        final int edgeIndex;
        final boolean forward;

        Arrival(int fromState, int edgeIndex, boolean forward) {
            this.fromState = fromState;
            this.edgeIndex = edgeIndex;
            this.forward = forward;
        }
    }

    private final class Search {
        final RouteRequest request;
        final SnapPoint start;
        final int startEdgeIndex;
        final List<SnapPoint> goals;
        final List<Integer> reachableGoals = new ArrayList<>();
        final Map<Integer, List<Integer>> goalsByEdgeIndex = new HashMap<>();
        final double minFactor;
        final Map<Integer, EdgeVerdict> verdicts = new HashMap<>();
        final Map<Integer, Double> costs = new HashMap<>();
        final Map<Integer, Double> g = new HashMap<>();
        final Map<Integer, Arrival> arrivals = new HashMap<>();
        final Set<Integer> closed = new HashSet<>();
        final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.f, b.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(b.g, a.g);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        });
        long seq;

        Search(RouteRequest request) {
            this.request = request;
            this.start = request.start();
            this.startEdgeIndex = snapshot.edgeIndex(start.edgeId());
            if (startEdgeIndex < 0) {
                throw new IllegalArgumentException("start edge " + start.edgeId() + " is not in the snapshot");
            }
            this.goals = request.goals();
            int startComponent = snapshot.componentOf(snapshot.edgeAt(startEdgeIndex));
            for (int k = 0; k < goals.size(); k++) {
                int ei = snapshot.edgeIndex(goals.get(k).edgeId());
                if (ei < 0) {
                    throw new IllegalArgumentException("goal edge " + goals.get(k).edgeId() + " is not in the snapshot");
                }
                if (snapshot.componentOf(snapshot.edgeAt(ei)) == startComponent) {
                    reachableGoals.add(k);
                    goalsByEdgeIndex.computeIfAbsent(ei, x -> new ArrayList<>()).add(k);
                }
            }
            this.minFactor = snapshot.minCostFactor(request.classCost());
        }

        static int goalState(int k) {
            return -2 - k;
        }

        static boolean isGoal(int state) {
            return state <= -2;
        }

        static int goalIndex(int state) {
            return -2 - state;
        }

        EdgeVerdict verdict(int edgeIndex) {
            return verdicts.computeIfAbsent(edgeIndex, i -> request.accessPolicy().check(snapshot.edgeAt(i)));
        }

        double edgeCost(int edgeIndex) {
            return costs.computeIfAbsent(edgeIndex, i -> snapshot.edgeCost(snapshot.edgeAt(i), request.classCost()));
        }

        double[] position(int state) {
            if (state == START_STATE) {
                return start.point();
            }
            if (isGoal(state)) {
                return goals.get(goalIndex(state)).point();
            }
            return snapshot.requireNode(state).position();
        }

        double heuristic(int state) {
            double[] p = position(state);
            double best = Double.POSITIVE_INFINITY;
            for (int k : reachableGoals) {
                double[] q = goals.get(k).point();
                double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            return Math.sqrt(best) * minFactor;
        }

        void relax(int from, int to, double cost, int edgeIndex, boolean forward) {
            if (closed.contains(to)) {
                return;
            }
            double tentative = g.get(from) + cost;
            Double known = g.get(to);
            if (known != null && tentative >= known) {
                return;
            }
            g.put(to, tentative);
            arrivals.put(to, new Arrival(from, edgeIndex, forward));
            open.add(new Entry(tentative + heuristic(to), tentative, seq++, to));
        }

        /** Goals on an edge reachable from {@code along} travelling in {@code forward} direction. */
        void relaxGoalsOnEdge(int from, int edgeIndex, double along, boolean forward, double partialFactor) {
            List<Integer> onEdge = goalsByEdgeIndex.get(edgeIndex);
            if (onEdge == null) {
                return;
            }
            for (int k : onEdge) {
                double goalAlong = goals.get(k).along();
                if (forward ? goalAlong >= along : goalAlong <= along) {
                    relax(from, goalState(k), Math.abs(goalAlong - along) * partialFactor, edgeIndex, forward);
                }
            }
        }

        void expandStart() {
            RoadEdge edge = snapshot.edgeAt(startEdgeIndex);
            EdgePolyline p = snapshot.polylineAt(startEdgeIndex);
            if (!verdict(startEdgeIndex).isUsable()) {
                // Phase 2d decision: a blocked start edge cannot be left. On the routing view (rev. 7 Part A) that is
                // only the stretch of the block itself - a gate door's piece, the inside of a denied region. A start
                // at one of its ends is at the node (a junction, or the split node before a door): leave from there.
                if (start.along() <= AT_NODE) {
                    relax(START_STATE, edge.fromNodeId(), 0, startEdgeIndex, false);
                } else if (start.along() >= p.length() - AT_NODE) {
                    relax(START_STATE, edge.toNodeId(), 0, startEdgeIndex, true);
                }
                return;
            }
            double perBlock = p.length() <= 0 ? 0 : edgeCost(startEdgeIndex) / p.length();
            double along = start.along();
            // forward: towards the To node
            relaxGoalsOnEdge(START_STATE, startEdgeIndex, along, true, perBlock);
            relax(START_STATE, edge.toNodeId(), (p.length() - along) * perBlock, startEdgeIndex, true);
            if (!edge.isOneway()) {
                relaxGoalsOnEdge(START_STATE, startEdgeIndex, along, false, perBlock);
                relax(START_STATE, edge.fromNodeId(), along * perBlock, startEdgeIndex, false);
            }
        }

        void expandNode(int nodeId) {
            for (int ei : snapshot.incidentEdgeIndexes(nodeId)) {
                RoadEdge edge = snapshot.edgeAt(ei);
                boolean forward = edge.fromNodeId() == nodeId;
                if (!forward && edge.isOneway()) {
                    continue;
                }
                if (!verdict(ei).isUsable()) {
                    continue;
                }
                EdgePolyline p = snapshot.polylineAt(ei);
                double perBlock = p.length() <= 0 ? 0 : edgeCost(ei) / p.length();
                relaxGoalsOnEdge(nodeId, ei, forward ? 0 : p.length(), forward, perBlock);
                relax(nodeId, edge.otherNode(nodeId), edgeCost(ei), ei, forward);
            }
        }

        /**
         * No goal reachable: the route to the reached point (the start or a node) nearest to a goal - as
         * close as the open roads go (N14). Null when the start itself is nearest.
         */
        Route runTowardsClosest() {
            g.put(START_STATE, 0.0);
            closed.add(START_STATE);
            expandStart();
            int best = START_STATE;
            double bestDistance = distanceToGoals(START_STATE);
            while (!open.isEmpty()) {
                Entry e = open.poll();
                if (closed.contains(e.state)) {
                    continue;
                }
                closed.add(e.state);
                if (isGoal(e.state)) {
                    return reconstruct(e.state, e.g);
                }
                double d = distanceToGoals(e.state);
                if (d < bestDistance - 1e-9) {
                    best = e.state;
                    bestDistance = d;
                }
                expandNode(e.state);
            }
            if (best == START_STATE) {
                return null;
            }
            return reconstructTo(best, SnapPoint.atNode(snapshot, best));
        }

        double distanceToGoals(int state) {
            double[] p = position(state);
            double best = Double.POSITIVE_INFINITY;
            for (int k : reachableGoals) {
                double[] q = goals.get(k).point();
                double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            return best;
        }

        Route run() {
            g.put(START_STATE, 0.0);
            closed.add(START_STATE);
            expandStart();
            while (!open.isEmpty()) {
                Entry e = open.poll();
                if (closed.contains(e.state)) {
                    continue;
                }
                closed.add(e.state);
                if (isGoal(e.state)) {
                    return reconstruct(e.state, e.g);
                }
                expandNode(e.state);
            }
            return null;
        }

        Route reconstruct(int goalState, double cost) {
            Route route = reconstructTo(goalState, goals.get(goalIndex(goalState)));
            assert Math.abs(route.cost() - cost) < 1e-6 : "route cost " + route.cost() + " != search cost " + cost;
            return route;
        }

        /** The route to a reached state: a goal (ends at its snap point) or a node (ends at the node). */
        Route reconstructTo(int endState, SnapPoint goal) {
            List<Route.Step> steps = new ArrayList<>();
            int state = endState;
            while (state != START_STATE) {
                Arrival a = arrivals.get(state);
                RoadEdge edge = snapshot.edgeAt(a.edgeIndex);
                EdgePolyline p = snapshot.polylineAt(a.edgeIndex);
                double entry = a.fromState == START_STATE ? start.along() : (a.forward ? 0 : p.length());
                double exit = isGoal(state) ? goal.along() : (a.forward ? p.length() : 0);
                EdgeVerdict v = verdict(a.edgeIndex);
                boolean leftAtTheNode = a.fromState == START_STATE && !v.isUsable(); // the player is at its node
                if (Math.abs(exit - entry) > 1e-9 && !leftAtTheNode) {
                    steps.add(Route.Step.of(edge, a.forward, entry, exit, v));
                }
                state = a.fromState;
            }
            Collections.reverse(steps);
            return Route.build(snapshot, start, goal, steps, request.classCost());
        }
    }
}
