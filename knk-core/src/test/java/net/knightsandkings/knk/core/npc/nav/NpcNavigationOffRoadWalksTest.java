package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * Link C1 (PandiO/knk-workspace#700): off-road starts and ends, and walk-only trips, are planned with the walk search
 * (the {@link OffRoadWalks} port) and walked as part of the route line; hops only when it finds nothing.
 */
class NpcNavigationOffRoadWalksTest {

    record RoadAsk(NavPoint from, NavPoint to, CompletableFuture<RoadPlan> answer) {
    }

    record LegAsk(NavPoint feet, NavPoint target, CompletableFuture<Prepared> answer) {
    }

    record WalkAsk(NavPoint feet, List<NavPoint> targets, double arrive, CompletableFuture<OffRoadWalks.Walk> answer) {
    }

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);
    private static final NavPoint START = new NavPoint(WORLD, 0.5, 65, 0.5);
    private static final NavPoint FAR = new NavPoint(WORLD, 100.5, 65, 0.5);

    private final FakeNavWorld world = new FakeNavWorld();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<RoadAsk> roadAsks = new ArrayList<>();
    private final List<LegAsk> legAsks = new ArrayList<>();
    private final List<WalkAsk> walkAsks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<NavigationResult> results = new ArrayList<>();
    private NpcNavigationService service;
    private FakeMover mover;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        RoadPlanner roads = (subject, from, to) -> {
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(new RoadAsk(from, to, answer));
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(new LegAsk(feet, target, answer));
            return answer;
        };
        OffRoadWalks walks = (subject, feet, targets, arrive) -> {
            CompletableFuture<OffRoadWalks.Walk> answer = new CompletableFuture<>();
            walkAsks.add(new WalkAsk(feet, List.copyOf(targets), arrive, answer));
            return answer;
        };
        service = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, System::nanoTime,
            log::add).withWalks(walks), 4);
        mover = new FakeMover(1, START);
        service.register(mover, NpcAccessProfile.DEFAULT, Settings.defaults().withCollidable(false));
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

    private void walkToTheEnd() {
        int guard = 0;
        while (results.isEmpty() && guard++ < 40) {
            mover.arrive();
            service.tick();
        }
    }

    private NavigationResult lastResult() {
        return results.get(results.size() - 1);
    }

    @Test
    void offRoadStartAndEndAreWalkedOnTheWalkSearchsPathAsPartOfTheLine() {
        // the road runs along z 20; the NPC starts and the goal lies 20 blocks off it
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));

        assertEquals(2, walkAsks.size(), "the walk to the road and the walk to the goal, at once");
        WalkAsk head = walkAsks.get(0);
        WalkAsk tail = walkAsks.get(1);
        assertEquals(START, head.feet(), "from the NPC's feet");
        assertEquals(floor(0.5, 64, 20.5), head.targets().get(0), "aimed at the road's start");
        assertTrue(head.targets().size() > 1, "and it may join the road further along: " + head.targets());
        assertEquals(NpcNavigationService.WALK_ARRIVE, head.arrive(), 1e-9);
        assertEquals(new NavPoint(WORLD, 100.5, 65, 20.5), tail.feet(), "the walk to the goal starts at the road's end");
        assertEquals(List.of(floor(100.5, 64, 0.5)), tail.targets());
        assertEquals(NpcNavState.PLANNING, service.state(mover.id()));

        head.answer().complete(OffRoadWalks.Walk.found(cells(0, 0, 0, 20), 0, "test"));
        tail.answer().complete(OffRoadWalks.Walk.found(cells(100, 20, 100, 0), 0, "test"));

        assertTrue(legAsks.isEmpty(), "no hops");
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        List<NavPoint> first = mover.follows.get(0);
        assertTrue(first.get(1).z() > first.get(0).z() && first.get(1).x() < 2, "it walks the path to the road: " + first);
        List<NavPoint> line = service.followerOf(mover.id()).line();
        assertEquals(floor(0.5, 64, 0.5), line.get(0), "the route line starts with the walk");
        assertEquals(floor(100.5, 64, 0.5), line.get(line.size() - 1), "and ends with the walk to the goal");
        assertEquals(2, service.offRoadWalks());
        assertEquals(0, service.offRoadHops());

        walkToTheEnd();
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
        assertTrue(mover.walks.isEmpty(), "every part walked as stretches, no local leg");
    }

    @Test
    void theRoadIsJoinedWhereTheWalkReachesIt() {
        // the road's start is behind a wall: the walk search reaches the road 9 blocks further along
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));
        WalkAsk head = walkAsks.get(0);
        int reached = 6; // 9 blocks along at 1.5 per point
        NavPoint join = head.targets().get(reached);
        assertEquals(9.5, join.x(), 1e-9);

        head.answer().complete(OffRoadWalks.Walk.found(cells(0, 0, 9, 20), reached, "test"));
        walkAsks.get(1).answer().complete(OffRoadWalks.Walk.found(cells(100, 20, 100, 0), 0, "test"));

        List<NavPoint> line = service.followerOf(mover.id()).line();
        assertFalse(line.stream().anyMatch(p -> p.x() < 9 && p.z() > 20), "the road before the join is not walked: "
            + line);
        assertTrue(log.stream().anyMatch(l -> l.contains("joins the road at 9 65 20")), log.toString());
    }

    @Test
    void anEndTheWalkSearchDoesNotReachIsWalkedInHopsAsBefore() {
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));
        walkAsks.get(0).answer().complete(OffRoadWalks.Walk.none(OffRoadWalks.Status.NO_PATH, "test"));
        walkAsks.get(1).answer().complete(OffRoadWalks.Walk.found(cells(100, 20, 100, 0), 0, "test"));

        assertEquals(1, legAsks.size(), "the start in hops: a local leg first");
        assertEquals(16.5, legAsks.get(0).target().z(), 1e-9, "a hop of hop-blocks towards the road's start");
        assertEquals(1, service.offRoadHops());
        assertEquals(1, service.offRoadWalks());
        assertTrue(log.stream().anyMatch(l -> l.contains("NO_PATH") && l.contains("walked in hops")), log.toString());
    }

    @Test
    void theWalkToTheGoalIsTriedFromFurtherBackAlongTheRoadWhenTheEndReachesNothing() {
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));
        walkAsks.get(0).answer().complete(OffRoadWalks.Walk.found(cells(0, 0, 0, 20), 0, "test"));

        walkAsks.get(1).answer().complete(OffRoadWalks.Walk.none(OffRoadWalks.Status.NO_PATH, "test"));
        assertEquals(3, walkAsks.size(), "asked again");
        NavPoint second = walkAsks.get(2).feet();
        assertTrue(second.x() <= 92.5 && second.x() >= 91, "8 blocks further back along the road: " + second);
        List<NavPoint> down = new ArrayList<>(cells(92, 20, 92, 0));
        down.addAll(cells(93, 0, 100, 0));
        walkAsks.get(2).answer().complete(OffRoadWalks.Walk.found(down, 0, "test"));

        List<NavPoint> line = service.followerOf(mover.id()).line();
        assertFalse(line.stream().anyMatch(p -> p.x() > 93 && p.z() > 19), "the road after that point is left out");
        assertEquals(floor(100.5, 64, 0.5), line.get(line.size() - 1));
        assertTrue(legAsks.isEmpty());
    }

    @Test
    void theWalkToTheGoalTriesAtMostItsTriesThenHops() {
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));
        walkAsks.get(0).answer().complete(OffRoadWalks.Walk.found(cells(0, 0, 0, 20), 0, "test"));
        for (int i = 0; i < NpcNavigationService.TAIL_TRIES; i++) {
            walkAsks.get(walkAsks.size() - 1).answer().complete(OffRoadWalks.Walk.none(OffRoadWalks.Status.NO_PATH, "x"));
        }
        assertEquals(1 + NpcNavigationService.TAIL_TRIES, walkAsks.size());
        assertEquals(NpcNavState.MOVING, service.state(mover.id()), "the walk to the road and the road");
        assertEquals(1, service.offRoadHops());
        walkToTheEndOrLegs();
        assertTrue(legAsks.size() >= 1, "the goal in hops from the road's end");
        assertEquals(20.5 - 16, legAsks.get(0).target().z(), 1e-9);
    }

    private void walkToTheEndOrLegs() {
        int guard = 0;
        while (legAsks.isEmpty() && guard++ < 40) {
            mover.arrive();
            service.tick();
        }
    }

    @Test
    void aGoalRightAboveTheRoadsEndIsWalkedTo() {
        // a tower: the road ends at its foot, the goal is 20 blocks up a stair inside it
        NavPoint top = new NavPoint(WORLD, 100.5, 85, 20.5);
        service.navigate(mover.id(), top, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 0.5), floor(100.5, 64, 20.5))));

        assertEquals(1, walkAsks.size(), "no walk to the road (the NPC is on it), one up the tower");
        assertEquals(List.of(floor(100.5, 84, 20.5)), walkAsks.get(0).targets());
    }

    @Test
    void aWalkOnlyTripIsOneWalkSearchWalkedAsALine() {
        NavPoint goal = new NavPoint(WORLD, 40.5, 65, 0.5);
        service.navigate(mover.id(), goal, results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.none(RoadPlanner.Status.OFF_ROAD, "end too far from a road"));

        assertEquals(1, walkAsks.size());
        assertEquals(List.of(floor(40.5, 64, 0.5)), walkAsks.get(0).targets());
        walkAsks.get(0).answer().complete(OffRoadWalks.Walk.found(cells(0, 0, 40, 0), 0, "test"));

        assertTrue(legAsks.isEmpty(), "no hops");
        assertEquals(1, mover.follows.size());
        walkToTheEnd();
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
        assertEquals(1, service.offRoadWalks());
    }

    @Test
    void aWalkOnlyTripTheSearchCannotPlanIsWalkedInHops() {
        service.navigate(mover.id(), new NavPoint(WORLD, 40.5, 65, 0.5), results::add);
        service.tick();
        roadAsks.get(0).answer().complete(RoadPlan.none(RoadPlanner.Status.OFF_ROAD, "end too far from a road"));
        walkAsks.get(0).answer().complete(OffRoadWalks.Walk.none(OffRoadWalks.Status.UNLOADED, "test"));

        assertEquals(1, legAsks.size());
        assertEquals(16.5, legAsks.get(0).target().x(), 1e-9, "hops of hop-blocks, as before");
        assertEquals(1, service.offRoadHops());
    }

    @Test
    void aWalkPortThatThrowsIsAFallbackNotACrash() {
        RoadPlanner roads = (subject, from, to) -> CompletableFuture.completedFuture(
            RoadPlan.none(RoadPlanner.Status.OFF_ROAD, "x"));
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(new LegAsk(feet, target, answer));
            return answer;
        };
        NpcNavigationService throwing = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world,
            metrics, System::nanoTime, log::add).withWalks((s, f, t, a) -> {
                throw new IllegalStateException("boom");
            }), 4);
        FakeMover m = new FakeMover(2, START);
        throwing.register(m, NpcAccessProfile.DEFAULT, Settings.defaults().withCollidable(false));
        throwing.navigate(m.id(), new NavPoint(WORLD, 40.5, 65, 0.5), results::add);
        throwing.tick();

        assertEquals(1, legAsks.size(), "hops");
        assertEquals(1, throwing.offRoadHops());
    }

    @Test
    void joinTargetsReachJoinReachBlocksAlongTheRoad() {
        List<NavPoint> road = cells(0, 0, 60, 0);
        List<NavPoint> targets = NpcNavigationService.joinTargets(road);
        assertEquals(road.get(0), targets.get(0));
        assertEquals(33, targets.size(), "0 to 32 blocks along");
        assertEquals(30, NpcNavigationService.indexBack(road, 38, 8));
        assertEquals(-1, NpcNavigationService.indexBack(road, 5, 8));
    }
    @Test
    void aMovingNpcLooksForAHatchAheadOnlyWhenItsProfileOpensDoors() {
        // link H1 (PandiO/knk-workspace#779): a closed roof hatch is opened like a door, so only with open-doors
        FakeMover shut = new FakeMover(2, START);
        service.register(shut, new NpcAccessProfile("no-doors", false, false, java.util.Set.of(), java.util.Set.of()),
            Settings.defaults().withCollidable(false));
        service.navigate(mover.id(), FAR, results::add);
        service.navigate(shut.id(), FAR, r -> { });
        service.tick();
        for (RoadAsk ask : List.copyOf(roadAsks)) {
            ask.answer().complete(RoadPlan.found(List.of(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5))));
        }
        for (WalkAsk ask : List.copyOf(walkAsks)) {
            ask.answer().complete(OffRoadWalks.Walk.found(List.of(floor(ask.feet().x(), 64, ask.feet().z()),
                ask.targets().get(0)), 0, "test"));
        }
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        assertEquals(NpcNavState.MOVING, service.state(shut.id()));
        int before = mover.hatchAsks;
        service.tick();
        service.tick();

        assertEquals(before + 2, mover.hatchAsks, "every tick while it moves");
        assertEquals(0, shut.hatchAsks, "never without open-doors: the closed hatch blocks");
    }
}
