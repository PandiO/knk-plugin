package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/** The per-NPC state machine against a fake mover, a fake road planner and a fake leg preparer. */
class NpcNavigationServiceTest {

    /** A mover that records its orders; the test moves it and fires the engine's events. */
    static final class FakeMover implements NpcMover {
        final int id;
        NavPoint position;
        boolean spawned = true;
        MoverListener listener;
        final List<List<NavPoint>> follows = new ArrayList<>();
        final List<NavPoint> walks = new ArrayList<>();
        final List<LocalLeg> legs = new ArrayList<>();
        int stops;
        boolean moving;

        FakeMover(int id, NavPoint feet) {
            this.id = id;
            this.position = feet;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String name() {
            return "npc-" + id;
        }

        @Override
        public boolean isSpawned() {
            return spawned;
        }

        @Override
        public Optional<NavPoint> position() {
            return spawned ? Optional.of(position) : Optional.empty();
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public void follow(List<NavPoint> waypoints) {
            follows.add(waypoints);
            lastWasFollow = true;
            moving = true;
        }

        @Override
        public void walkTo(NavPoint target) {
            throw new AssertionError("navigation never walks without K&K's rules");
        }

        @Override
        public void walkTo(NavPoint target, LocalLeg leg) {
            walks.add(target);
            legs.add(leg);
            lastWasFollow = false;
            moving = true;
        }

        @Override
        public void stop() {
            stops++;
            moving = false;
        }

        @Override
        public boolean isMoving() {
            return moving;
        }

        @Override
        public void remove() {
            spawned = false;
        }

        @Override
        public void setListener(MoverListener listener) {
            this.listener = listener;
        }

        /** The engine walked the last order to its end. */
        void arrive() {
            if (lastWasFollow) {
                List<NavPoint> last = follows.get(follows.size() - 1);
                position = last.get(last.size() - 1);
            } else {
                position = walks.get(walks.size() - 1);
            }
            moving = false;
            listener.arrived(this);
        }

        boolean lastWasFollow;

        /** What the engine's own path search found for the running local leg (feet positions). */
        List<NavPoint> pathAhead = List.of();

        @Override
        public List<NavPoint> pathAhead() {
            return pathAhead;
        }
    }

    record RoadAsk(AccessSubject subject, NavPoint from, NavPoint to, CompletableFuture<RoadPlan> answer) {
    }

    record LegAsk(AccessSubject subject, NavPoint feet, NavPoint target, CompletableFuture<Prepared> answer) {
    }

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);
    private static final NavPoint START = new NavPoint(WORLD, 0.5, 65, 0.5);
    private static final NavPoint FAR = new NavPoint(WORLD, 100.5, 65, 0.5);

    private final FakeNavWorld world = new FakeNavWorld();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<RoadAsk> roadAsks = new ArrayList<>();
    private final List<LegAsk> legAsks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<NavigationResult> results = new ArrayList<>();
    private long nanos;
    private NpcNavigationService service;
    private FakeMover mover;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        service = service(4);
        mover = register(1, START, Settings.defaults());
    }

    private NpcNavigationService service(int maxPlansInFlight) {
        RoadPlanner roads = (subject, from, to) -> {
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(new RoadAsk(subject, from, to, answer));
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(new LegAsk(subject, feet, target, answer));
            return answer;
        };
        return new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, () -> nanos += 1000,
            log::add), maxPlansInFlight);
    }

    private FakeMover register(int id, NavPoint feet, Settings settings) {
        FakeMover m = new FakeMover(id, feet);
        service.register(m, NpcAccessProfile.DEFAULT, settings);
        return m;
    }

    private static Settings settings(int maxRetries) {
        Settings d = Settings.defaults();
        return new Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), maxRetries, d.noRouteWaitTicks(),
            d.walkOnlyBlocks());
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            service.tick();
        }
    }

    private RoadAsk lastRoadAsk() {
        return roadAsks.get(roadAsks.size() - 1);
    }

    private LegAsk lastLegAsk() {
        return legAsks.get(legAsks.size() - 1);
    }

    private static RoadPlan road(NavPoint... floorPoints) {
        return RoadPlan.found(List.of(floorPoints));
    }

    private void goFar() {
        assertTrue(service.navigate(mover.id(), FAR, results::add));
        service.tick();
    }

    private NavigationResult lastResult() {
        return results.get(results.size() - 1);
    }

    // ==================== the road line (trail mode) ====================

    @Test
    void walksTheRoadLineStretchByStretchAndArrives() {
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        service.navigate(mover.id(), FAR, results::add);
        assertEquals(NpcNavState.QUEUED, service.state(mover.id()));
        service.tick();
        assertEquals(NpcNavState.PLANNING, service.state(mover.id()));
        assertEquals(1, roadAsks.size());
        assertEquals(floor(0.5, 64, 0.5), lastRoadAsk().from(), "planned from the NPC's own floor point");
        assertEquals(floor(100.5, 64, 0.5), lastRoadAsk().to());

        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));

        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        assertEquals(1, mover.follows.size(), "the first stretch only, not the whole route");
        assertTrue(mover.follows.get(0).get(mover.follows.get(0).size() - 1).x() < 40);
        int guard = 0;
        while (results.isEmpty() && guard++ < 20) {
            mover.arrive();
        }
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
        assertEquals(1, results.size());
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        assertEquals(4, mover.follows.size(), "100 blocks in stretches of 32");
        assertTrue(mover.walks.isEmpty(), "on the road the engine's path search is not used");
        assertTrue(legAsks.isEmpty());
        assertEquals(1, metrics.total(Event.ARRIVED));
        assertEquals(4, metrics.total(Event.STRETCH));
    }

    @Test
    void theNextStretchIsHandedOverBeforeTheCurrentOneEnds() {
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));
        List<NavPoint> first = mover.follows.get(0);

        ticks(10);
        assertEquals(1, mover.follows.size(), "far from the stretch's end: nothing yet");

        mover.position = first.get(first.size() - 2); // within 4 blocks of the end, still walking
        ticks(NpcNavigationService.HANDOVER_TICKS);

        assertEquals(2, mover.follows.size());
        assertTrue(mover.follows.get(1).get(0).x() > first.get(first.size() - 1).x() - 1e-9, "continues where the first ends");
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        ticks(NpcNavigationService.HANDOVER_TICKS * 2);
        assertEquals(2, mover.follows.size(), "handed over once");
    }

    @Test
    void offRoadEndsAreWalkedAsLocalLegsBeforeAndAfterTheLine() {
        // the road runs along z 20; the NPC starts and the goal lies off it
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 20.5), floor(100.5, 64, 20.5)));

        assertEquals(1, legAsks.size(), "first a local leg towards the road");
        assertEquals(NpcNavState.PLANNING, service.state(mover.id()));
        assertTrue(mover.follows.isEmpty());
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        assertSame(LEG, mover.legs.get(0), "the engine's search gets K&K's access for the leg");
        assertEquals(16.5, mover.walks.get(0).z(), 1e-9, "20 blocks to the road: a hop of 16, then the rest");
        assertEquals(65, mover.walks.get(0).y(), 1e-9, "a feet position");
        mover.arrive();
        service.tick();
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        assertEquals(20.5, mover.walks.get(1).z(), 1e-9);

        mover.arrive();
        assertEquals(1, mover.follows.size(), "then the line");
        int guard = 0;
        while (legAsks.size() == 2 && guard++ < 20) {
            mover.arrive();
            service.tick();
        }
        assertEquals(3, legAsks.size(), "then local legs from the road's end to the goal");
        assertEquals(4.5, lastLegAsk().target().z(), 1e-9);
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        mover.arrive();
        service.tick();
        assertEquals(floor(100.5, 64, 0.5), lastLegAsk().target());
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        mover.arrive();
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
    }

    @Test
    void aShortTripIsOneLocalLegWithoutARoadPlan() {
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), results::add);
        service.tick();

        assertTrue(roadAsks.isEmpty());
        assertEquals(1, legAsks.size());
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        mover.arrive();
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
    }

    @Test
    void anNpcAlreadyAtItsGoalArrivesAtOnce() {
        service.navigate(mover.id(), new NavPoint(WORLD, 1.0, 65, 0.5), results::add);
        service.tick();

        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
        assertTrue(roadAsks.isEmpty() && legAsks.isEmpty());
    }

    // ==================== no route ====================

    @Test
    void aRuleBlockWaitsReportsOnceAndAsksAgain() {
        goFar();
        lastRoadAsk().answer().complete(RoadPlan.blocked(List.of(), "the West Gate is closed",
            BlockingElement.gate(7, "the West Gate"), List.of(), null));

        assertEquals(NpcNavState.WAITING, service.state(mover.id()));
        NavigationResult.Blocked blocked = assertInstanceOf(NavigationResult.Blocked.class, lastResult());
        assertEquals("the West Gate", blocked.blockingElement().name());
        assertEquals(BlockingElement.Kind.GATE, blocked.blockingElement().kind());
        assertTrue(mover.follows.isEmpty() && mover.walks.isEmpty(), "no improvising across terrain");

        ticks(Settings.defaults().noRouteWaitTicks() - 1);
        assertEquals(1, roadAsks.size(), "waiting, not retrying every tick");
        ticks(2);
        assertEquals(2, roadAsks.size());
        lastRoadAsk().answer().complete(RoadPlan.none(RoadPlanner.Status.NO_ROUTE, "no route on the road network"));
        assertEquals(1, results.size(), "blocked is reported once per blocked spell");
        assertEquals(2, metrics.total(Event.NO_ROUTE_WAIT));

        ticks(Settings.defaults().noRouteWaitTicks() + 1);
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()), "the order goes on by itself when the way opens");
    }

    @Test
    void roadsThatDoNotConnectFailInsteadOfWaiting() {
        goFar();
        lastRoadAsk().answer().complete(RoadPlan.none(RoadPlanner.Status.DIFFERENT_COMPONENTS, null));

        NavigationResult.Failed failed = assertInstanceOf(NavigationResult.Failed.class, lastResult());
        assertTrue(failed.reason().startsWith("no road connects"), failed.reason());
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        ticks(300);
        assertEquals(1, roadAsks.size(), "never asked again");
        assertEquals(1, metrics.total(Event.FAILED));
        assertEquals(0, metrics.total(Event.NO_ROUTE_WAIT));
    }

    @Test
    void anEndTooFarFromARoadIsWalkedInShortHopsWhenTheTripIsShort() {
        service.navigate(mover.id(), new NavPoint(WORLD, 40.5, 65, 0.5), results::add);
        service.tick();
        lastRoadAsk().answer().complete(RoadPlan.none(RoadPlanner.Status.OFF_ROAD, "end too far from a road"));

        assertEquals(16.5, lastLegAsk().target().x(), 1e-9, "hops of hop-blocks, not one long leg");
        for (int hop = 0; hop < 3; hop++) {
            lastLegAsk().answer().complete(Prepared.ready(LEG));
            mover.arrive();
            service.tick();
        }
        assertEquals(3, legAsks.size());
        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
    }

    @Test
    void aLongTripWithoutARoadFails() {
        goFar();
        lastRoadAsk().answer().complete(RoadPlan.none(RoadPlanner.Status.NO_NETWORK, "no road network for world world"));

        NavigationResult.Failed failed = assertInstanceOf(NavigationResult.Failed.class, lastResult());
        assertTrue(failed.reason().contains("walk-only-blocks"), failed.reason());
        assertTrue(legAsks.isEmpty(), "no straight line across terrain");
    }

    @Test
    void aGoalInAnotherWorldFails() {
        service.navigate(mover.id(), new NavPoint("nether", 0, 65, 0), results::add);
        service.tick();

        assertInstanceOf(NavigationResult.Failed.class, lastResult());
    }

    // ==================== stuck: watchdog and bounded retries ====================

    @Test
    void theWatchdogSendsAnNpcWithoutProgressOnARecoveryLeg() {
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));
        mover.position = new NavPoint(WORLD, 5.5, 65, 0.5);

        // moving about without getting further along the line is not progress
        for (int i = 0; i < 200 && legAsks.isEmpty(); i++) {
            mover.position = new NavPoint(WORLD, 5.5, 65, 0.5 + (i % 2) * 0.4);
            service.tick();
        }

        assertEquals(1, metrics.total(Event.STUCK_NO_PROGRESS));
        assertEquals(1, legAsks.size(), "one local leg to recover");
        assertTrue(lastLegAsk().target().x() > 8 && lastLegAsk().target().x() < 14, "a few blocks further along the line");
        assertTrue(mover.stops >= 1);
        assertTrue(log.get(0).contains("no progress") && log.get(0).contains("5 65 0"), "logged with the position: " + log);

        lastLegAsk().answer().complete(Prepared.ready(LEG));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        mover.arrive();

        assertEquals(1, metrics.total(Event.RECOVERED));
        assertEquals(2, mover.follows.size(), "back on the line");
        assertTrue(mover.follows.get(1).get(0).x() >= mover.walks.get(0).x() - 1, "from the recovery target on");
        assertEquals(0, metrics.total(Event.RETRIES_EXHAUSTED));
    }

    @Test
    void anNpcMakingProgressIsLeftAlone() {
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));

        for (int i = 0; i < 100; i++) {
            mover.position = new NavPoint(WORLD, 0.5 + i * 0.2, 65, 0.5);
            service.tick();
        }

        assertEquals(0, metrics.total(Event.STUCK_NO_PROGRESS));
        assertTrue(legAsks.isEmpty());
    }

    @Test
    void aLocalLegIsRetriedThenTheRouteIsPlannedAgainThenTheOrderFails() {
        service.unregister(mover.id());
        mover = register(2, START, settings(1));
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), results::add);
        service.tick();
        lastLegAsk().answer().complete(Prepared.ready(LEG));

        mover.listener.stuck(mover); // retry 1 of 1: the same hop again
        assertEquals(NpcNavState.QUEUED, service.state(mover.id()));
        service.tick();
        assertEquals(2, legAsks.size());
        assertEquals(legAsks.get(0).target(), legAsks.get(1).target());
        lastLegAsk().answer().complete(Prepared.ready(LEG));

        mover.listener.stuck(mover); // retries used up: wait, then plan the route again (replan 1)
        assertEquals(NpcNavState.WAITING, service.state(mover.id()));
        assertEquals(1, metrics.total(Event.RETRIES_EXHAUSTED));
        assertTrue(results.isEmpty());

        for (int replan = 2; replan <= NpcNavigationService.MAX_REPLANS + 1; replan++) {
            ticks(Settings.defaults().noRouteWaitTicks() + 1);
            lastLegAsk().answer().complete(Prepared.ready(LEG));
            mover.listener.stuck(mover);
            service.tick();
            lastLegAsk().answer().complete(Prepared.ready(LEG));
            mover.listener.stuck(mover);
        }

        NavigationResult.Failed failed = assertInstanceOf(NavigationResult.Failed.class, lastResult());
        assertTrue(failed.reason().contains("retries and replans used up"), failed.reason());
        assertEquals(NpcNavigationService.MAX_REPLANS + 1, metrics.total(Event.RETRIES_EXHAUSTED));
        assertEquals(2L * (NpcNavigationService.MAX_REPLANS + 1), metrics.total(Event.STUCK_ENGINE));
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        assertEquals(1, results.size());
    }

    @Test
    void aWalkedLegGivesTheRetriesBack() {
        service.unregister(mover.id());
        mover = register(2, START, settings(1));
        service.navigate(mover.id(), new NavPoint(WORLD, 40.5, 65, 0.5), results::add);
        service.tick();
        lastRoadAsk().answer().complete(RoadPlan.none(RoadPlanner.Status.OFF_ROAD, "off road"));

        for (int hop = 0; hop < 3; hop++) {
            lastLegAsk().answer().complete(Prepared.ready(LEG));
            mover.listener.stuck(mover); // one retry per hop is within the budget
            service.tick();
            lastLegAsk().answer().complete(Prepared.ready(LEG));
            mover.arrive();
            service.tick();
        }

        assertInstanceOf(NavigationResult.Arrived.class, lastResult());
        assertEquals(0, metrics.total(Event.RETRIES_EXHAUSTED));
    }

    // ==================== local leg preparation ====================

    @Test
    void anUnloadedChunkOnALegWaitsAtTheEdgeAndNeverFails() {
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), results::add);
        service.tick();

        for (int round = 0; round < NpcNavigationService.MAX_PREPARE_FAILURES + 2; round++) {
            lastLegAsk().answer().complete(Prepared.of(LocalLegPreparer.Status.UNLOADED, "unloaded chunk"));
            assertEquals(NpcNavState.WAITING, service.state(mover.id()));
            ticks(Settings.defaults().noRouteWaitTicks() + 1);
        }

        assertTrue(results.isEmpty());
        assertEquals(NpcNavigationService.MAX_PREPARE_FAILURES + 2, metrics.total(Event.UNLOADED_WAIT));
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
    }

    @Test
    void aLegThatCannotBePreparedFailsTheOrderAfterAFewTries() {
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), results::add);
        service.tick();

        for (int round = 0; round < NpcNavigationService.MAX_PREPARE_FAILURES; round++) {
            lastLegAsk().answer().complete(Prepared.of(LocalLegPreparer.Status.TOO_LARGE, "box of 80 chunks"));
            ticks(NpcNavigationService.PREPARE_RETRY_TICKS + 1);
        }

        NavigationResult.Failed failed = assertInstanceOf(NavigationResult.Failed.class, lastResult());
        assertTrue(failed.reason().contains("TOO_LARGE"), failed.reason());
    }

    @Test
    void aStretchInAnUnloadedChunkWaitsAndGoesOnWhenItLoads() {
        world.loaded = (x, z) -> x < 16;
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));

        assertEquals(NpcNavState.WAITING, service.state(mover.id()));
        assertTrue(mover.follows.isEmpty());
        assertEquals(1, metrics.total(Event.UNLOADED_WAIT));

        world.loaded = (x, z) -> true;
        ticks(Settings.defaults().noRouteWaitTicks() + 1);
        assertEquals(1, mover.follows.size());
        assertEquals(1, roadAsks.size(), "the road route is kept: only the stretch was missing");
    }

    // ==================== planning queue ====================

    @Test
    void plansInFlightAreLimitedAndTheQueueIsFirstInFirstOut() {
        service = service(2);
        roadAsks.clear();
        List<FakeMover> movers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FakeMover m = register(10 + i, START, Settings.defaults());
            movers.add(m);
            service.navigate(m.id(), FAR, results::add);
        }

        service.tick();

        assertEquals(2, roadAsks.size());
        assertEquals(2, service.plansInFlight());
        assertEquals(3, service.queued());
        assertEquals(NpcNavState.PLANNING, service.state(10));
        assertEquals(NpcNavState.QUEUED, service.state(12));
        ticks(5);
        assertEquals(2, roadAsks.size(), "no more than the limit, however long it takes");

        roadAsks.get(0).answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));
        assertEquals(1, service.plansInFlight());
        service.tick();
        assertEquals(3, roadAsks.size());
        assertEquals(NpcNavState.PLANNING, service.state(12), "the one that waited longest");
        assertEquals(java.util.Map.of(WORLD, java.util.Map.of(NpcNavState.MOVING, 1, NpcNavState.PLANNING, 2,
            NpcNavState.QUEUED, 2)), service.states());
    }

    @Test
    void anAnswerForAnOrderThatIsGoneIsIgnoredAndItsSlotIsFreed() {
        goFar();
        RoadAsk stale = lastRoadAsk();
        service.cancel(mover.id(), "changed its mind");
        assertEquals(1, service.plansInFlight());

        stale.answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));

        assertEquals(0, service.plansInFlight());
        assertTrue(mover.follows.isEmpty());
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
    }

    @Test
    void aPlannerThatThrowsIsAFailedPlanNotACrash() {
        RoadPlanner broken = (subject, from, to) -> {
            throw new IllegalStateException("boom");
        };
        service = new NpcNavigationService(new NpcNavigationService.Ports(broken, (s, f, t) -> new CompletableFuture<>(),
            world, metrics, null, null), 1);
        mover = register(3, START, Settings.defaults());
        service.navigate(mover.id(), FAR, results::add);

        for (int i = 0; i < NpcNavigationService.MAX_PREPARE_FAILURES; i++) {
            ticks(NpcNavigationService.PREPARE_RETRY_TICKS + 1);
        }

        assertInstanceOf(NavigationResult.Failed.class, lastResult());
        assertEquals(0, service.plansInFlight());
    }

    // ==================== pause and resume (NAV-11) ====================

    @Test
    void aChunkUnloadPausesAndTheRespawnPlansAgainFromWhereTheNpcIs() {
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));

        mover.spawned = false;
        mover.listener.despawned(mover, true);
        assertEquals(NpcNavState.PAUSED, service.state(mover.id()));
        assertEquals(1, metrics.total(Event.PAUSED));
        ticks(200);
        assertEquals(1, roadAsks.size(), "a paused NPC costs no plans");
        assertTrue(results.isEmpty(), "the order is kept");

        mover.spawned = true;
        mover.position = new NavPoint(WORLD, 30.5, 65, 0.5);
        mover.listener.respawned(mover);
        ticks(3);

        assertEquals(1, metrics.total(Event.RESUMED));
        assertEquals(2, roadAsks.size());
        assertEquals(floor(30.5, 64, 0.5), lastRoadAsk().from());
    }

    @Test
    void anOrderForADespawnedNpcStartsWhenItIsBack() {
        mover.spawned = false;
        service.navigate(mover.id(), FAR, results::add);
        ticks(5);
        assertEquals(NpcNavState.PAUSED, service.state(mover.id()));
        assertTrue(roadAsks.isEmpty());

        mover.spawned = true;
        mover.listener.respawned(mover);
        ticks(3);
        assertEquals(1, roadAsks.size());
    }

    @Test
    void anNpcThatDiesEndsItsOrder() {
        goFar();
        mover.spawned = false;
        mover.listener.despawned(mover, false);

        assertInstanceOf(NavigationResult.Cancelled.class, lastResult());
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
    }

    // ==================== callers ====================

    @Test
    void cancelStopsTheNpcAndReportsIt() {
        goFar();
        lastRoadAsk().answer().complete(road(floor(0.5, 64, 0.5), floor(100.5, 64, 0.5)));
        int stops = mover.stops;

        assertTrue(service.cancel(mover.id(), "behavior changed"));

        NavigationResult.Cancelled cancelled = assertInstanceOf(NavigationResult.Cancelled.class, lastResult());
        assertEquals("behavior changed", cancelled.reason());
        assertEquals(stops + 1, mover.stops);
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        assertFalse(service.cancel(mover.id(), "again"), "nothing to cancel");
    }

    @Test
    void aNewOrderReplacesTheRunningOneAndTheCallbackMayOrderAgain() {
        goFar();
        List<NavigationResult> second = new ArrayList<>();
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), result -> {
            second.add(result);
            if (result instanceof NavigationResult.Arrived && second.size() == 1) {
                service.navigate(mover.id(), new NavPoint(WORLD, 20.5, 65, 0.5), second::add); // behavior's next goal
            }
        });

        assertEquals("replaced", assertInstanceOf(NavigationResult.Cancelled.class, lastResult()).reason());
        service.tick();
        lastLegAsk().answer().complete(Prepared.ready(LEG));
        mover.arrive();

        assertEquals(1, second.size());
        assertEquals(NpcNavState.QUEUED, service.state(mover.id()), "the next order is already queued");
    }

    @Test
    void unregisteredMoversAreUnknown() {
        service.unregister(mover.id());

        assertFalse(service.navigate(mover.id(), FAR, results::add));
        assertNull(service.state(mover.id()));
        assertNull(mover.listener);
        assertEquals(0, service.count());
    }

    @Test
    void theSubjectIsTheNpcWithItsProfileAndTheRegionsItStandsIn() {
        world.regions = Set.of("town_1");
        service.unregister(mover.id());
        NpcAccessProfile guard = new NpcAccessProfile("guard", false, false, Set.of(), Set.of("throne_room"));
        FakeMover m = new FakeMover(7, START);
        service.register(m, guard, Settings.defaults());
        service.navigate(m.id(), FAR, results::add);
        service.tick();

        AccessSubject subject = lastRoadAsk().subject();
        assertEquals("npc-7", subject.name());
        assertEquals(Set.of("town_1"), subject.currentRegions());
        assertEquals(AccessSubject.RegionOverride.DENY, subject.regionOverride("throne_room"));
        assertTrue(subject.playerId().isEmpty(), "never a borrowed player");
    }

    @Test
    void mainThreadTimeIsMeasuredPerTick() {
        goFar();
        assertTrue(service.lastTickNanos() > 0);
        net.knightsandkings.knk.core.metrics.PrometheusWriter out = new net.knightsandkings.knk.core.metrics.PrometheusWriter();
        metrics.collect(out);
        assertTrue(out.render().contains("knk_npc_nav_main_thread_seconds_count 1"), out.render());
        assertTrue(out.render().contains("knk_npc_nav_queue_wait_seconds_count 1"), out.render());
    }
}
