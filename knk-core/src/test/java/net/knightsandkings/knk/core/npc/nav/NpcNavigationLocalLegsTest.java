package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * Clearance-aware local legs in the navigation service (implementation plan 4c.3 decision 8, link M6): with
 * {@code local-legs-clearance} off every leg is the engine's; on, K&K's own search gives the waypoints, the engine's
 * search is the fallback, and the stats count {@code k&k}, {@code engine} and {@code none}.
 */
class NpcNavigationLocalLegsTest {

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);
    private static final NavPoint START = new NavPoint(WORLD, 0.5, 65, 0.5);
    private static final NavPoint NEAR = new NavPoint(WORLD, 10.5, 65, 0.5);

    private final FakeNavWorld world = new FakeNavWorld();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<CompletableFuture<Prepared>> legAsks = new ArrayList<>();
    private final List<NavigationResult> results = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private NpcNavigationService service;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(answer);
            return answer;
        };
        RoadPlanner roads = (subject, from, to) -> new CompletableFuture<>();
        service = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, System::nanoTime,
            log::add), 4);
    }

    private static Settings settings(boolean localLegsClearance) {
        Settings d = Settings.defaults();
        return new Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), d.maxRetries(), d.noRouteWaitTicks(),
            d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(), d.cornerClearance(), d.waypointMargin(),
            d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(), d.wallClearance(), localLegsClearance);
    }

    private FakeMover npc(boolean key) {
        FakeMover m = new FakeMover(1, START);
        service.register(m, NpcAccessProfile.DEFAULT, settings(key));
        return m;
    }

    private void shortTrip(FakeMover m, LocalLeg leg) {
        assertTrue(service.navigate(m.id(), NEAR, results::add));
        service.tick();
        legAsks.get(legAsks.size() - 1).complete(Prepared.ready(leg));
    }

    @Test
    void theKeyIsOffByDefaultAndEveryLegIsTheEnginesThen() {
        assertEquals(false, Settings.defaults().localLegsClearance());
        FakeMover m = npc(false);

        shortTrip(m, LEG);

        assertEquals(1, m.walks.size(), "the engine's search walks the leg");
        assertTrue(m.follows.isEmpty());
        m.arrive();
        assertInstanceOf(NavigationResult.Arrived.class, results.get(0));
        assertEquals(1, metrics.total(Event.LOCAL_LEG_ENGINE));
        assertEquals(0, metrics.total(Event.LOCAL_LEG_KNK));
        assertEquals(0, service.localLegs().searches(), "with the key off K&K does not search");
    }

    @Test
    void withTheKeyOnKAndKsOwnLegIsWalkedAsWaypoints() {
        world.addWall(5, 64, 0); // a post on the straight line
        FakeMover m = npc(true);

        shortTrip(m, LEG);

        assertTrue(m.walks.isEmpty(), "no engine search");
        assertEquals(1, m.follows.size());
        List<NavPoint> way = m.follows.get(0);
        NavPoint last = way.get(way.size() - 1);
        assertEquals(10.5, last.x(), 1e-9);
        assertEquals(65, last.y(), 1e-9, "feet positions");
        assertTrue(way.size() >= 2, "round the post, not through it: " + way);
        assertEquals(1, metrics.total(Event.LOCAL_LEG_KNK));
        m.arrive();
        assertInstanceOf(NavigationResult.Arrived.class, results.get(0));
        assertEquals(0, metrics.total(Event.LOCAL_LEG_ENGINE));
        assertEquals("local legs: k&k 1, engine 0, none 0", NpcNavigationMetrics.localLegsLine(
            metrics.total(Event.LOCAL_LEG_KNK), metrics.total(Event.LOCAL_LEG_ENGINE), metrics.total(Event.LOCAL_LEG_NONE)));
    }

    @Test
    void whenKAndKFindsNothingTheEnginesSearchIsTheFallbackAndItsNoPathCountsNone() {
        // the leg's access denies a band across the way: K&K finds no path
        CellAccess band = (x, feetY, z) -> x == 5 ? CellAccess.BLOCKED : 0.0;
        LocalLeg leg = new LocalLeg(band, -1000, 0, -1000, 1000, 255, 1000, true);
        FakeMover m = npc(true);

        shortTrip(m, leg);

        assertEquals(1, m.walks.size(), "the engine's search takes the leg");
        assertTrue(m.follows.isEmpty());
        assertTrue(log.stream().anyMatch(line -> line.contains("K&K found no path")), log.toString());
        m.listener.stuck(m, NpcMover.StuckKind.NO_PATH);
        assertEquals(1, metrics.total(Event.LOCAL_LEG_NONE));
        assertEquals(0, metrics.total(Event.LOCAL_LEG_ENGINE));
        assertEquals(1, metrics.total(Event.RECOVERY_NO_PATH));
    }

    @Test
    void anEngineLegThatFoundItsWayCountsEngineOnce() {
        CellAccess band = (x, feetY, z) -> x == 5 ? CellAccess.BLOCKED : 0.0;
        FakeMover m = npc(true);

        shortTrip(m, new LocalLeg(band, -1000, 0, -1000, 1000, 255, 1000, true));
        m.arrive();

        assertEquals(1, metrics.total(Event.LOCAL_LEG_ENGINE));
        assertEquals(0, metrics.total(Event.LOCAL_LEG_NONE));
    }

    @Test
    void aKAndKLegWithoutProgressOpensAStuckSpell() {
        FakeMover m = npc(true);
        shortTrip(m, LEG);
        assertEquals(1, m.follows.size());

        // the body stands still: waypoint lists report no stuck (NAV-17), the leg's watchdog does
        for (int i = 0; i < Settings.defaults().noProgressTicks() + 2 * NpcNavigationService.WATCHDOG_TICKS; i++) {
            service.tick();
        }

        assertEquals(1, metrics.total(Event.STUCK_NO_PROGRESS), log.toString());
    }

    @Test
    void aKAndKLegThatProgressesOpensNoSpell() {
        FakeMover m = npc(true);
        shortTrip(m, LEG);

        for (int i = 0; i < 3 * Settings.defaults().noProgressTicks(); i++) {
            if (i % 10 == 0) {
                m.position = new NavPoint(WORLD, Math.min(10.0, m.position.x() + 0.6), 65, 0.5);
            }
            service.tick();
        }

        assertEquals(0, metrics.total(Event.STUCK_NO_PROGRESS), log.toString());
    }
}
