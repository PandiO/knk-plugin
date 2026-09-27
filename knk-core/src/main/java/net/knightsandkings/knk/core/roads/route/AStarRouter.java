package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            if (!verdict(startEdgeIndex).isUsable()) {
                return; // Phase 2d decision: a blocked start edge cannot be left
            }
            EdgePolyline p = snapshot.polylineAt(startEdgeIndex);
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
            SnapPoint goal = goals.get(goalIndex(goalState));
            List<Route.Step> steps = new ArrayList<>();
            int state = goalState;
            while (state != START_STATE) {
                Arrival a = arrivals.get(state);
                RoadEdge edge = snapshot.edgeAt(a.edgeIndex);
                EdgePolyline p = snapshot.polylineAt(a.edgeIndex);
                double entry = a.fromState == START_STATE ? start.along() : (a.forward ? 0 : p.length());
                double exit = isGoal(state) ? goal.along() : (a.forward ? p.length() : 0);
                if (Math.abs(exit - entry) > 1e-9) {
                    steps.add(Route.Step.of(edge, a.forward, entry, exit, verdict(a.edgeIndex)));
                }
                state = a.fromState;
            }
            Collections.reverse(steps);
            Route route = Route.build(snapshot, start, goal, steps, request.classCost());
            assert Math.abs(route.cost() - cost) < 1e-6 : "route cost " + route.cost() + " != search cost " + cost;
            return route;
        }
    }
}
