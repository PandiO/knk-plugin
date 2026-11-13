package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/** The {@code /knk npc nav} test driver: spawns spread over ticks, trips back and forth, its numbers. */
class NpcNavRunnerTest {

    private static final String WORLD = "world";
    private static final NavPoint FROM = new NavPoint(WORLD, 0.5, 65, 0.5);
    private static final NavPoint TO = new NavPoint(WORLD, 60.5, 65, 0.5);

    static final class Mover implements NpcMover {
        final int id;
        final String name;
        NavPoint position;
        boolean removed;
        MoverListener listener;
        NavPoint lastTarget;

        Mover(int id, String name, NavPoint at) {
            this.id = id;
            this.name = name;
            this.position = at;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isSpawned() {
            return !removed;
        }

        @Override
        public Optional<NavPoint> position() {
            return removed ? Optional.empty() : Optional.of(position);
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public void follow(List<NavPoint> waypoints) {
            lastTarget = waypoints.get(waypoints.size() - 1);
        }

        @Override
        public void walkTo(NavPoint target) {
            throw new AssertionError("route NPCs walk under K&K's rules");
        }

        @Override
        public void walkTo(NavPoint target, LocalLeg leg) {
            lastTarget = target;
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isMoving() {
            return lastTarget != null;
        }

        @Override
        public void remove() {
            removed = true;
        }

        @Override
        public void setListener(MoverListener listener) {
            this.listener = listener;
        }

        void arrive() {
            position = lastTarget;
            lastTarget = null;
            listener.arrived(this);
        }
    }

    @TempDir
    Path folder;

    private final List<Mover> movers = new ArrayList<>();
    private final List<NavPoint> roadGoals = new ArrayList<>();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private NpcNavigationService service;
    private NpcNavRunner runner;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        NavWorld world = new NavWorld() {
            @Override
            public boolean isLoaded(String w, int x, int z) {
                return true;
            }

            @Override
            public boolean isStandableFloor(String w, int x, int floorY, int z) {
                return floorY == 64;
            }

            @Override
            public Set<String> regionsAt(String w, int x, int feetY, int z) {
                return Set.of();
            }
        };
        NpcMoverFactory factory = new NpcMoverFactory() {
            @Override
            public Optional<String> unavailableReason() {
                return Optional.empty();
            }

            @Override
            public NpcMover spawn(String name, NavPoint at, MoverOptions options) {
                Mover mover = new Mover(movers.size() + 1, name, at);
                movers.add(mover);
                return mover;
            }
        };
        RoadPlanner roads = (subject, from, to) -> {
            roadGoals.add(to);
            return CompletableFuture.completedFuture(RoadPlanner.RoadPlan.found(List.of(from, to)));
        };
        LocalLegPreparer legs = (subject, feet, target) -> CompletableFuture.completedFuture(LocalLegPreparer.Prepared
            .ready(new LocalLeg(CellAccess.OPEN, -100, 0, -100, 100, 255, 100, true)));
        service = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, null, null), 4);
        runner = new NpcNavRunner(factory, service, NavigationSettings.defaults(), metrics, world,
            new NpcRouteStore(folder.toFile(), Logger.getLogger("test")),
            new NpcNavLog(folder, Logger.getLogger("test")), 2, at -> List.of());
        runner.setEnd(true, FROM);
        runner.setEnd(false, TO);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            runner.tick();
            service.tick();
            runner.sampleService();
        }
    }

    private void walkToTheEnd(Mover mover) {
        for (int i = 0; i < 20 && service.state(mover.id()) == NpcNavState.MOVING; i++) {
            mover.arrive();
            ticks(1);
        }
    }

    @Test
    void spawnsAreSpreadOverTicksNearTheRoutesStart() {
        runner.spawn(5, NpcAccessProfile.DEFAULT);
        assertEquals(0, movers.size(), "nothing spawns in the command itself");
        assertEquals(5, runner.pendingSpawns());

        ticks(1);
        assertEquals(2, movers.size());
        ticks(1);
        assertEquals(4, movers.size());
        ticks(1);
        assertEquals(5, movers.size());
        assertEquals(5, runner.count());
        assertEquals(0, runner.pendingSpawns());

        for (Mover mover : movers) {
            assertTrue(mover.position.horizontalDistance(FROM) <= 5, "near the start: " + mover.position);
            assertEquals(65, mover.position.y(), "standing on the floor");
            assertTrue(mover.name.startsWith("knk-nav-"));
        }
        assertEquals(5, metrics.total(NpcNavigationMetrics.Event.SPAWNED));
    }

    @Test
    void npcsWalkTheRouteBackAndForth() {
        runner.spawn(1, NpcAccessProfile.DEFAULT);
        ticks(2);
        Mover mover = movers.get(0);
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        assertEquals(64, roadGoals.get(0).y(), 1e-9);
        assertEquals(60.5, roadGoals.get(0).x(), 1e-9, "first towards the route's end");

        walkToTheEnd(mover);
        assertEquals(NpcNavState.IDLE, service.state(mover.id()));
        assertTrue(mover.position.horizontalDistance(TO) < 2);

        ticks(NpcNavRunner.TURN_TICKS + 2);
        assertEquals(NpcNavState.MOVING, service.state(mover.id()), "and back after a short rest");
        assertEquals(0.5, roadGoals.get(roadGoals.size() - 1).x(), 1e-9);

        List<String> report = runner.report(12, 3);
        assertTrue(report.get(3).startsWith("trips 1 "), report.get(3));
        assertTrue(report.stream().anyMatch(line -> line.contains("12 cells asked, 3 blocked")));
        assertTrue(report.stream().anyMatch(line -> line.contains("engine block rules removed 0")));
        assertTrue(report.stream().anyMatch(line -> line.startsWith("failures: none")));
    }

    @Test
    void gotoSendsEveryNpcToOnePointAndPatrolPutsThemBackOnTheRoute() {
        runner.spawn(2, NpcAccessProfile.DEFAULT);
        ticks(2);
        roadGoals.clear();

        assertEquals(2, runner.goTo(new NavPoint(WORLD, 30.5, 65, 30.5)));
        ticks(1);
        assertEquals(2, roadGoals.size());
        assertEquals(30.5, roadGoals.get(0).z(), 1e-9);
        for (Mover mover : movers) {
            walkToTheEnd(mover);
        }
        ticks(NpcNavRunner.TURN_TICKS + 2);
        assertEquals(NpcNavState.IDLE, service.state(movers.get(0).id()), "they stay where they were sent");

        assertEquals(2, runner.patrol());
        ticks(1);
        assertEquals(NpcNavState.MOVING, service.state(movers.get(0).id()));
    }

    @Test
    void clearRemovesTheNpcsAndTheSpawnsStillQueued() {
        runner.spawn(6, NpcAccessProfile.DEFAULT);
        ticks(1);

        assertEquals(2, runner.clear());

        assertEquals(0, runner.count());
        assertEquals(0, runner.pendingSpawns());
        assertEquals(0, service.count());
        assertTrue(movers.stream().allMatch(mover -> mover.removed));
        ticks(3);
        assertEquals(2, movers.size(), "the queued spawns are gone");
    }

    @Test
    void theBaselineGivesTheCostPerNpcAndATraceWritesAFile() throws Exception {
        for (int i = 0; i < 10; i++) {
            runner.sampleTick(10.0);
        }
        assertEquals(10.0, runner.baseline(), 1e-9);
        runner.spawn(2, NpcAccessProfile.DEFAULT);
        ticks(2);
        for (int i = 0; i < NpcNavRunner.TICK_WINDOW; i++) {
            runner.sampleTick(10.06);
        }

        assertTrue(runner.report(0, 0).get(1).contains("-> 0.030 ms per NPC"), runner.report(0, 0).get(1));

        String started = runner.startTrace(FROM, null, 1);
        assertTrue(started.startsWith("Tracing knk-nav-"), started);
        ticks(25);
        try (var files = Files.list(folder.resolve("npc-logs"))) {
            Path trace = files.filter(file -> file.getFileName().toString().contains("_trace_")).findFirst().orElseThrow();
            List<String> lines = Files.readAllLines(trace);
            assertTrue(lines.size() > 15 && lines.get(lines.size() - 1).equals("# trace ended"), lines.toString());
        }
        assertEquals("No test NPC nobody", runner.startTrace(FROM, "nobody", 1));
    }

    @Test
    void theReportHasTheRuleNumbers() {
        runner.spawn(1, NpcAccessProfile.DEFAULT);
        ticks(2);
        metrics.count(net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.REPLAN_EVENT, 3);
        String rules = runner.report(0, 0).stream().filter(line -> line.startsWith("rules:")).findFirst().orElseThrow();
        assertTrue(rules.startsWith("rules: trespasses 0 (must be 0) | rule checks "), rules);
        assertTrue(rules.contains("replans: recheck 0, event 3"), rules);
        assertTrue(rules.endsWith("walked to the blocking element 0, woken by its event 0, waiting there now 0"), rules);
    }

    @Test
    void percentilesAndTheDistanceFromALine() {
        double[] window = new double[100];
        for (int i = 0; i < 100; i++) {
            window[i] = i + 1;
        }
        assertEquals(95, NpcNavRunner.percentile(window, 100, 95), 1e-9);
        assertEquals(50.5, NpcNavRunner.mean(window, 100), 1e-9);
        assertTrue(Double.isNaN(NpcNavRunner.mean(window, 0)));
        assertEquals(3, NpcNavRunner.segmentDistance(new NavPoint(WORLD, 0, 64, 0), new NavPoint(WORLD, 10, 64, 0), 5, 3),
            1e-9);
        assertFalse(runner.routes().current().from() == null, "the ends are saved as the current route");
    }

    // ==================== groups (PandiO/knk-workspace#377) ====================

    /** Lets every mover that has an order reach its target, tick by tick, until {@code done} or the ticks run out. */
    private void walk(java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            for (Mover mover : List.copyOf(movers)) {
                if (mover.lastTarget != null && !mover.removed) {
                    mover.arrive();
                }
            }
            ticks(1);
        }
    }

    @Test
    void aGroupWalksTheRouteWithOneRoadPlanAndTurnsRound() {
        runner.spawnGroup(5, NpcAccessProfile.DEFAULT, net.knightsandkings.knk.core.npc.nav.group.Formation.Type.RANKS);
        assertEquals(0, runner.groupCount(), "the group forms when everybody is out");
        ticks(3);

        assertEquals(5, movers.size());
        assertEquals(5, runner.count());
        assertEquals(1, runner.groupCount());
        ticks(1);
        assertEquals(1, roadGoals.size(), "the leader's plan is the group's only one");
        assertEquals(60.5, roadGoals.get(0).x(), 1e-9);
        assertEquals(NpcNavState.MOVING, service.state(movers.get(0).id()));
        for (Mover follower : movers.subList(1, 5)) {
            assertEquals(NpcNavState.IDLE, service.state(follower.id()), "a follower has no order of its own");
        }
        String line = runner.report(0, 0).stream().filter(l -> l.startsWith("groups ")).findFirst().orElseThrow();
        assertTrue(line.startsWith("groups 1 (5 NPCs, ranks): trips 0 | folds 0, reforms 0, folded now 0 | own legs back 0"),
            line);
        assertTrue(line.endsWith("most plans at once 0"), "this test's planner answers at once: " + line);

        walk(() -> roadGoals.size() >= 2, 1500);

        assertEquals(2, roadGoals.size(), "arrived, rested and sent back: one more plan");
        assertEquals(0.5, roadGoals.get(1).x(), 1e-9);
        assertTrue(runner.report(0, 0).stream().anyMatch(l -> l.startsWith("groups 1 (5 NPCs, ranks): trips 1 ")));
        assertTrue(runner.report(0, 0).get(3).startsWith("trips 0 "), "a group's trip is not five NPC trips");
        for (Mover follower : movers.subList(1, 5)) {
            assertTrue(follower.position.x() > 50, "the followers came along: " + follower.position);
        }
    }

    @Test
    void aGroupTakesItsFormationFromTheSettingsUnlessTheCommandNamesOne() throws Exception {
        runner.spawnGroup(3, NpcAccessProfile.DEFAULT, null);
        runner.spawnGroup(3, NpcAccessProfile.DEFAULT, net.knightsandkings.knk.core.npc.nav.group.Formation.Type.ESCORT_RING);
        ticks(4);

        assertEquals(2, runner.groupCount());
        assertTrue(runner.report(0, 0).stream().anyMatch(l -> l.startsWith("groups 2 (6 NPCs, column/escort-ring)")));
        try (var files = Files.list(folder.resolve("npc-logs"))) {
            String log = Files.readString(files.filter(f -> f.getFileName().toString().endsWith("_npc-nav.log"))
                .findFirst().orElseThrow());
            assertTrue(log.contains("column, spacing 2.0, group-formation from default"), log);
            assertTrue(log.contains("escort-ring, spacing 2.0, group-formation from assignment"), log);
        }
    }

    @Test
    void gotoAndPatrolOrderTheGroupNotItsMembers() {
        runner.spawnGroup(4, NpcAccessProfile.DEFAULT, null);
        ticks(3);
        roadGoals.clear();

        assertEquals(4, runner.goTo(new NavPoint(WORLD, 30.5, 65, 30.5)));
        ticks(1);
        assertEquals(1, roadGoals.size(), "one plan for the group");
        assertEquals(30.5, roadGoals.get(0).z(), 1e-9);

        assertEquals(4, runner.patrol());
        ticks(1);
        assertEquals(2, roadGoals.size());
        assertEquals(0.5, roadGoals.get(1).z(), 1e-9, "back on the route");
    }

    @Test
    void clearDisbandsGroupsAlsoWhileTheyAreStillSpawning() {
        runner.spawnGroup(4, NpcAccessProfile.DEFAULT, null);
        ticks(3);
        runner.spawnGroup(6, NpcAccessProfile.DEFAULT, null);
        ticks(1);
        assertEquals(1, runner.groupCount());
        assertEquals(6, runner.count());

        assertEquals(6, runner.clear());

        assertEquals(0, runner.groupCount());
        assertEquals(0, runner.count());
        assertEquals(0, service.count());
        assertTrue(movers.stream().allMatch(mover -> mover.removed));
        ticks(5);
        assertEquals(6, movers.size(), "the queued spawns are gone");
        assertTrue(runner.report(0, 0).stream().anyMatch(l -> l.startsWith("groups 0 (0 NPCs): trips 0 ")));
    }
}
