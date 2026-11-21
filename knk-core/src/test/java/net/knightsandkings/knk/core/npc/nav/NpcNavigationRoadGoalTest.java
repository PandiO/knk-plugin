package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.route.SnapPoint;

/**
 * Link G1 (PandiO/knk-workspace#814): with {@code road-goal-reachable} an NPC's route to a point destination ends at the
 * road point that the off-road chain's rule picks ({@code RoadGoalChoice}, F2/F3): today's goal and the candidates of
 * the {@link RoadGoals} port, each with a road route and one walk search, the least road plus weighted walk winning;
 * C1's join when none is reached.
 */
class NpcNavigationRoadGoalTest {

    record RoadAsk(NavPoint from, NavPoint to, CompletableFuture<RoadPlan> answer) {
    }

    record WalkAsk(NavPoint feet, List<NavPoint> targets, CompletableFuture<OffRoadWalks.Walk> answer) {
    }

    /** The NPC stands on the road's start; the road runs east along z 20. */
    private static final NavPoint START = new NavPoint(WORLD, 0.5, 65, 20.5);
    /** The destination, 20 blocks north of the road's end. */
    private static final NavPoint GOAL = new NavPoint(WORLD, 100.5, 65, 0.5);
    /** Today's road: to the point nearest the goal, a dead end (#780's case). */
    private static final List<NavPoint> TODAY = List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5));
    /** Another road point: the end of a street that comes round to the goal's side. */
    private static final NavPoint OTHER = floor(95.5, 64, 0.5);
    private static final List<NavPoint> VIA_OTHER = List.of(floor(0.5, 64, 20.5), floor(60.5, 64, 20.5),
        floor(60.5, 64, -9.5), floor(95.5, 64, -9.5), OTHER);
    /** A point only the other road passes. */
    private static final NavPoint ROUND = floor(60.5, 64, -9.5);

    private final FakeNavWorld world = new FakeNavWorld();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<RoadAsk> roadAsks = new ArrayList<>();
    private final List<WalkAsk> walkAsks = new ArrayList<>();
    private final List<Integer> candidateAsks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<NavigationResult> results = new ArrayList<>();
    private final Set<Integer> closedGates = new HashSet<>();
    private List<RoadGoals.Candidate> candidates = List.of(candidate(1, floor(100.5, 64, 20.5)),
        candidate(2, OTHER));
    private NpcNavigationService service;
    private FakeMover mover;

    private static RoadGoals.Candidate candidate(int edge, NavPoint floor) {
        return new RoadGoals.Candidate(floor, new SnapPoint(edge, 0, 0, new double[] {floor.x(), floor.y(), floor.z()},
            0, 0));
    }

    private void start(Settings settings, boolean withGoals) {
        metrics.setEnabled(true);
        RoadPlanner roads = (subject, from, to) -> {
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(new RoadAsk(from, to, answer));
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> new CompletableFuture<Prepared>();
        OffRoadWalks walks = (subject, feet, targets, arrive) -> {
            CompletableFuture<OffRoadWalks.Walk> answer = new CompletableFuture<>();
            walkAsks.add(new WalkAsk(feet, List.copyOf(targets), answer));
            return answer;
        };
        RoadGoals goals = (roadEnd, goalFloor, max) -> {
            candidateAsks.add(max);
            return candidates;
        };
        AccessRules rules = new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return id -> true;
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return closedGates.contains(doorId);
            }

            @Override
            public Optional<GateBox> doorBox(int doorId) {
                return Optional.empty();
            }
        };
        NpcNavigationService.Ports ports = new NpcNavigationService.Ports(roads, legs, world, metrics, System::nanoTime,
            log::add, rules).withWalks(walks);
        service = new NpcNavigationService(withGoals ? ports.withRoadGoals(goals) : ports, 4);
        mover = new FakeMover(1, START);
        service.register(mover, NpcAccessProfile.DEFAULT, settings);
    }

    private static Settings on() {
        return Settings.defaults().withCollidable(false).withRoadGoalReachable(true);
    }

    /** {@code settings} with another walk weight. */
    private static Settings weighted(Settings d, double weight) {
        return new Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), d.maxRetries(), d.noRouteWaitTicks(),
            d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(), d.cornerClearance(), d.waypointMargin(),
            d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(), d.wallClearance(),
            d.localLegsClearance(), d.plazaLines(), d.plazaCorridorBlocks(), d.crowdAwareness(), d.collidable(),
            d.playersPushNpcs(), d.pushReturn(), d.pushExemptAfter(), d.pushExemptWindowTicks(), d.pushExemptTicks(),
            d.pushTeleportTicks(), d.roadGoalReachable(), weight);
    }

    /** Floor points one block apart in a straight line from (x0, z0) to (x1, z1) at floor 64, both ends included. */
    private static List<NavPoint> cells(int x0, int z0, int x1, int z1) {
        List<NavPoint> out = new ArrayList<>();
        int n = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + Integer.signum(x1 - x0) * Math.min(i, Math.abs(x1 - x0));
            int z = z0 + Integer.signum(z1 - z0) * Math.min(i, Math.abs(z1 - z0));
            out.add(floor(x + 0.5, 64, z + 0.5));
        }
        return out;
    }

    private static OffRoadWalks.Walk found(List<NavPoint> path) {
        return OffRoadWalks.Walk.found(path, 0, "test");
    }

    private static OffRoadWalks.Walk noPath() {
        return OffRoadWalks.Walk.none(OffRoadWalks.Status.NO_PATH, "test");
    }

    private void order() {
        service.navigate(mover.id(), GOAL, results::add);
        service.tick();
    }

    private List<NavPoint> line() {
        return service.followerOf(mover.id()).line();
    }

    @Test
    void offByDefaultTheRouteEndsAtTheRoutersGoalAsC1() {
        assertFalse(Settings.defaults().roadGoalReachable(), "road-goal-reachable is off by default");
        assertEquals(4.0, Settings.defaults().roadGoalWalkWeight(), 1e-9, "the players' default weight");
        start(Settings.defaults().withCollidable(false), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));

        assertTrue(candidateAsks.isEmpty(), "no candidates asked for");
        assertEquals(1, walkAsks.size(), "C1's walk to the goal from the road's end");
        assertEquals(new NavPoint(WORLD, 100.5, 65, 20.5), walkAsks.get(0).feet());
        assertEquals(0, service.roadGoalChoices());
    }

    @Test
    void withoutTheCandidatesPortTheKeyChangesNothing() {
        start(on(), false);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));

        assertEquals(1, walkAsks.size(), "C1's walk to the goal");
        assertEquals(1, roadAsks.size());
    }

    @Test
    void aDeadEndRoadGoalGivesWayToAnotherRoadTheWalkReaches() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));

        assertEquals(List.of(NpcNavigationService.ROAD_GOAL_CANDIDATES), candidateAsks, "at most 12 candidates");
        assertEquals(12, NpcNavigationService.ROAD_GOAL_CANDIDATES);
        assertEquals(1, walkAsks.size(), "one candidate at a time: today's first");
        assertEquals(new NavPoint(WORLD, 100.5, 65, 20.5), walkAsks.get(0).feet());
        assertEquals(List.of(floor(100.5, 64, 0.5)), walkAsks.get(0).targets(), "a walk search to the destination");
        walkAsks.get(0).answer().complete(noPath());

        assertEquals(2, roadAsks.size(), "then a road route to the other road point");
        assertEquals(OTHER, roadAsks.get(1).to());
        assertEquals(floor(0.5, 64, 20.5), roadAsks.get(1).from(), "from where the NPC's route starts");
        assertEquals(NpcNavState.PLANNING, service.state(mover.id()));
        roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER));
        assertEquals(2, walkAsks.size());
        assertEquals(new NavPoint(WORLD, 95.5, 65, 0.5), walkAsks.get(1).feet(), "and a walk from its end");
        walkAsks.get(1).answer().complete(found(cells(95, 0, 100, 0)));

        assertEquals(2, walkAsks.size(), "the chosen walk is taken as it is, not searched again");
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        List<NavPoint> line = line();
        assertEquals(floor(100.5, 64, 0.5), line.get(line.size() - 1), "the line ends at the destination");
        assertTrue(line.contains(ROUND), "by the other road: " + line);
        assertFalse(line.stream().anyMatch(p -> p.x() > 61 && p.z() > 19), "not to the dead end: " + line);
        assertEquals(1, service.roadGoalChoices());
        assertEquals(1, service.roadGoalsMoved());
        assertTrue(log.stream().anyMatch(l -> l.contains("road goal for") && l.contains("#1 edge 1")
            && l.contains("not reached on foot") && l.contains("#2 edge 2") && l.contains("CHOSEN")), log.toString());

        int guard = 0;
        while (results.isEmpty() && guard++ < 60) {
            mover.arrive();
            service.tick();
        }
        assertInstanceOf(NavigationResult.Arrived.class, results.get(results.size() - 1));
    }

    @Test
    void theLeastRoadPlusWeightedWalkWins() {
        // today's: road 100 + walk 20; the other: road 60 + 30 + 35 + 10 = 135, walk 5
        for (double weight : new double[] {4.0, 1.0}) {
            roadAsks.clear();
            walkAsks.clear();
            log.clear();
            start(weighted(on(), weight), true);
            order();
            roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
            walkAsks.get(0).answer().complete(found(cells(100, 20, 100, 0)));
            roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER));
            walkAsks.get(1).answer().complete(found(cells(95, 0, 100, 0)));

            List<NavPoint> line = line();
            if (weight == 4.0) {
                // 100 + 4 * 20 = 180 against 135 + 4 * 5 = 155: the road carries the route further
                assertTrue(line.contains(ROUND), "weight 4: the other road: " + line);
            } else {
                // 120 against 140: the shorter trip
                assertTrue(line.contains(floor(100.5, 64, 20.5)), "weight 1: today's road: " + line);
                assertEquals(0, service.roadGoalsMoved());
            }
        }
    }

    /**
     * PandiO/knk-workspace#848, developer decision 2026-11-21: no road point reaches the goal on foot (a roof behind a
     * closed hatch for an NPC that may not open it). The NPC walks to the road point nearest the goal, with no walk
     * search or hops after it, and its order ends there as failed; a replan routes there again.
     */
    @Test
    void noCandidateReachedWalksToTheRoadPointNearestTheGoalAndFails() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(noPath());
        roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER));
        walkAsks.get(1).answer().complete(noPath());

        assertEquals(2, walkAsks.size(), "no further walk search: neither C1's join nor hops");
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        List<NavPoint> line = line();
        assertEquals(OTHER, line.get(line.size() - 1), "the road point nearest the goal (5 blocks, today's is 20)");
        assertTrue(line.contains(ROUND), "by its road: " + line);
        assertEquals(1, service.roadGoalsMoved());
        assertEquals(1, service.roadGoalsUnreachable());
        assertTrue(log.stream().anyMatch(l -> l.contains("road goal for") && l.contains("none reached on foot")
            && l.contains("the road point nearest the goal, #2 (95 65 0)") && l.contains("the order ends there as failed")),
            log.toString());

        int guard = 0;
        while (results.isEmpty() && guard++ < 60) {
            mover.arrive();
            service.tick();
        }
        assertEquals(2, walkAsks.size(), "no walk search at the road's end either");
        NavigationResult.Failed failed = assertInstanceOf(NavigationResult.Failed.class, results.get(0));
        assertTrue(failed.reason().contains("is not reachable on foot from any of the 2 road points tried")
            && failed.reason().contains("stopped at the road point nearest it, 95 65 0"), failed.reason());
    }

    @Test
    void aReplanOfAnUnreachableGoalRoutesToTheNearestRoadPointAgainWithoutAWalk() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(noPath());
        roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER,
            List.of(new RoadPlanner.GateCrossing(7, floor(60.5, 64, 10.5)))));
        walkAsks.get(1).answer().complete(noPath());

        closedGates.add(7);
        service.onGateChanged(7);
        for (int i = 0; i < 3; i++) {
            service.tick();
        }
        assertEquals(3, roadAsks.size(), "one replan, " + roadAsks);
        assertEquals(OTHER, roadAsks.get(2).to(), "to the nearest road point, not the destination");
        roadAsks.get(2).answer().complete(RoadPlan.found(VIA_OTHER));
        assertEquals(2, walkAsks.size(), "no walk to the goal after the replan");
        assertEquals(1, candidateAsks.size(), "no second choice");
        List<NavPoint> line = line();
        assertEquals(OTHER, line.get(line.size() - 1));
    }

    @Test
    void theNearestRoadPointCanBeTodaysGoal() {
        // today's road ends 20 blocks from the goal; the other road point is put 40 blocks off
        NavPoint far = floor(60.5, 64, -39.5);
        candidates = List.of(candidate(1, floor(100.5, 64, 20.5)), candidate(2, far));
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(noPath());
        roadAsks.get(1).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(60.5, 64, 20.5), far)));
        walkAsks.get(1).answer().complete(noPath());

        List<NavPoint> line = line();
        assertEquals(floor(100.5, 64, 20.5), line.get(line.size() - 1), "today's road end is the nearest");
        assertEquals(0, service.roadGoalsMoved());
        assertEquals(1, service.roadGoalsUnreachable());
        assertTrue(log.stream().anyMatch(l -> l.contains("the road point nearest the goal, #1 (100 65 20)")),
            log.toString());
    }

    @Test
    void aCandidateWithoutARoadRouteIsLeftOut() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(found(cells(100, 20, 100, 0)));
        roadAsks.get(1).answer().complete(RoadPlan.none(RoadPlanner.Status.NO_ROUTE, "test"));

        assertEquals(1, walkAsks.size(), "no walk from a road point no route reaches");
        assertTrue(line().contains(floor(100.5, 64, 20.5)), "today's goal");
        assertTrue(log.stream().anyMatch(l -> l.contains("#2 edge 2") && l.contains("no road route")), log.toString());
    }

    @Test
    void aReplanRoutesToTheChosenGoalWithoutChoosingAgain() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(noPath());
        roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER,
            List.of(new RoadPlanner.GateCrossing(7, floor(60.5, 64, 10.5)))));
        walkAsks.get(1).answer().complete(found(cells(95, 0, 100, 0)));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));

        closedGates.add(7);
        service.onGateChanged(7);
        for (int i = 0; i < 3; i++) {
            service.tick();
        }

        assertEquals(3, roadAsks.size(), "the gate on the route closed: one replan, " + roadAsks);
        assertEquals(OTHER, roadAsks.get(2).to(), "to the chosen road goal, not the destination");
        assertEquals(1, candidateAsks.size(), "no second choice");
        roadAsks.get(2).answer().complete(RoadPlan.found(VIA_OTHER));
        assertEquals(new NavPoint(WORLD, 95.5, 65, 0.5), walkAsks.get(walkAsks.size() - 1).feet(),
            "C1's walk to the goal from the chosen goal");
    }

    @Test
    void aNewOrderChoosesAgain() {
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));
        walkAsks.get(0).answer().complete(noPath());
        roadAsks.get(1).answer().complete(RoadPlan.found(VIA_OTHER));
        walkAsks.get(1).answer().complete(found(cells(95, 0, 100, 0)));

        order();
        assertEquals(floor(100.5, 64, 0.5), roadAsks.get(roadAsks.size() - 1).to(), "the new order asks for the destination");
    }

    @Test
    void oneCandidateOnlyIsTodaysGoalAsC1() {
        candidates = List.of(candidate(1, floor(100.5, 64, 20.5)));
        start(on(), true);
        order();
        roadAsks.get(0).answer().complete(RoadPlan.found(TODAY));

        assertEquals(1, candidateAsks.size());
        assertEquals(1, walkAsks.size(), "C1's walk to the goal");
        assertEquals(0, service.roadGoalChoices());
    }
}
