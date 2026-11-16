package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * Crowd awareness in the navigation service (implementation plan 4c.4 item 4.2, link M4): the key off changes nothing;
 * with it on, two NPCs meeting at a doorway take it in turns (the second is held at its queue spot, not stuck, and
 * walks its leg on with the same capture), an NPC behind another opens no stuck spell, and a recovery side-step skips a
 * cell where another NPC stands.
 */
class NpcNavigationCrowdTest {

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);

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
        // a wall along x = 10 from z -10 to 10 with a one-block doorway at z = 0
        for (int z = -10; z <= 10; z++) {
            if (z != 0) {
                world.addWall(10, 64, z);
            }
        }
    }

    private static Settings settings(boolean crowd) {
        Settings d = Settings.defaults();
        return new Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), d.maxRetries(), d.noRouteWaitTicks(),
            d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(), d.cornerClearance(), d.waypointMargin(),
            d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(), d.wallClearance(),
            d.localLegsClearance(), d.plazaLines(), d.plazaCorridorBlocks(), crowd);
    }

    private static NavPoint feet(double x, double z) {
        return new NavPoint(WORLD, x, 65, z);
    }

    /** The straight engine path from {@code from} to {@code to}, a point per block. */
    private static List<NavPoint> path(NavPoint from, NavPoint to) {
        List<NavPoint> out = new ArrayList<>();
        double len = from.horizontalDistance(to);
        for (int i = 1; i <= Math.ceil(len); i++) {
            double t = Math.min(1, i / len);
            out.add(feet(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t));
        }
        return out;
    }

    private FakeMover npc(int id, NavPoint at, boolean crowd) {
        FakeMover m = new FakeMover(id, at);
        service.register(m, NpcAccessProfile.DEFAULT, settings(crowd));
        return m;
    }

    /** Sends an NPC on a short trip (one engine leg) and lets the engine's path run straight to the goal. */
    private void trip(FakeMover m, NavPoint goal) {
        assertTrue(service.navigate(m.id(), goal, results::add));
        service.tick();
        legAsks.get(legAsks.size() - 1).complete(Prepared.ready(LEG));
        m.pathAhead = path(m.position, goal);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            service.tick();
        }
    }

    @Test
    void theKeyIsOffByDefaultAndChangesNothingThen() {
        assertFalse(Settings.defaults().crowdAwareness());
        assertFalse(NavigationSettings.CROWD_AWARENESS.defaultValue());
        FakeMover a = npc(1, feet(7.5, 0.5), false);
        FakeMover b = npc(2, feet(13.5, 0.5), false);
        trip(a, feet(14.5, 0.5));
        trip(b, feet(5.5, 0.5));
        ticks(20);
        assertEquals(0, a.stops + b.stops, "nobody is held");
        assertEquals(0, service.occupancy().rebuilds(), "no occupancy without the key");
        assertEquals(0, metrics.total(Event.CROWD_WAIT));
    }

    @Test
    void twoNpcsAtADoorwayTakeItInTurnsTheSecondIsHeldNotStuck() {
        FakeMover a = npc(1, feet(7.5, 0.5), true);
        FakeMover b = npc(2, feet(13.5, 0.5), true);
        trip(a, feet(14.5, 0.5));
        ticks(5); // a's update: it comes first
        trip(b, feet(5.5, 0.5));
        ticks(5);
        assertTrue(service.crowd().hasTurn(1));
        assertTrue(service.crowdHeld(2), "b waits for its turn");
        assertEquals(NpcNavState.WAITING, service.state(2));
        assertEquals(1, b.stops);
        assertTrue(b.lastWasFollow, "b walks to its queue spot");
        NavPoint spot = b.follows.get(b.follows.size() - 1).get(0);
        assertTrue(spot.x() >= 11.9 && Math.abs(spot.z() - 0.5) > 0.4, "on its side, off the line: " + spot);
        assertEquals(1, metrics.total(Event.CROWD_WAIT));
        assertTrue(log.stream().anyMatch(l -> l.contains("npc-2 waits for its turn at the narrow place")), log.toString());

        b.listener.stuck(b, NpcMover.StuckKind.STATIONARY); // the engine's stop or a report while held
        ticks(200); // ten seconds held: no watchdog, no stuck spell
        assertTrue(service.crowdHeld(2));
        assertFalse(service.stuckSpellOpen(2));
        assertEquals(0, metrics.total(Event.STUCK_ENGINE) + metrics.total(Event.STUCK_NO_PROGRESS));

        a.position = feet(12.5, 0.5); // a is through
        ticks(5);
        assertFalse(service.crowdHeld(2), "then b goes");
        assertEquals(NpcNavState.MOVING, service.state(2));
        assertEquals(2, b.walks.size(), "b walks its leg on");
        assertSame(b.legs.get(0), b.legs.get(1), "with the same capture");
        assertTrue(service.crowd().longestWaitTicks() >= 200);
        b.position = feet(5.5, 0.5);
        b.arrive();
        assertInstanceOf(NavigationResult.Arrived.class, results.get(results.size() - 1));
    }

    @Test
    void anNpcBehindAnotherOpensNoStuckSpell() {
        FakeMover a = npc(1, feet(3.5, 5.5), true);
        npc(2, feet(4.5, 5.5), true); // stands right ahead of a, no order
        trip(a, feet(9.5, 5.5));
        ticks(5);
        a.listener.stuck(a, NpcMover.StuckKind.STATIONARY); // collision on: the engine gives up behind the other
        assertFalse(service.stuckSpellOpen(1), "a wait, not a stuck spell");
        assertTrue(service.crowdHeld(1));
        assertEquals(1, metrics.total(Event.CROWD_WAIT));
        assertTrue(log.stream().anyMatch(l -> l.contains("npc-1 waits behind another NPC")), log.toString());
        ticks(NpcNavigationService.BEHIND_RETRY_TICKS + 5);
        assertFalse(service.crowdHeld(1), "it tries again");
        assertEquals(2, a.walks.size());
        assertEquals(0, metrics.spells().open());
    }

    @Test
    void withTheKeyOffTheSameStandOpensASpell() {
        FakeMover a = npc(1, feet(3.5, 5.5), false);
        npc(2, feet(4.5, 5.5), false);
        trip(a, feet(9.5, 5.5));
        ticks(5);
        a.listener.stuck(a, NpcMover.StuckKind.STATIONARY);
        assertTrue(service.stuckSpellOpen(1));
        assertEquals(0, metrics.total(Event.CROWD_WAIT));
    }

    @Test
    void aSideStepSkipsACellWhereAnotherNpcStands() {
        // a wall right ahead of the NPC (east): the side-step is one block west, unless someone stands there
        world.addWall(6, 64, 5);
        FakeMover a = npc(1, feet(5.5, 5.5), true);
        npc(2, feet(4.5, 5.5), true);
        assertTrue(service.navigate(a.id(), feet(14.5, 3.5), results::add));
        service.tick();
        legAsks.get(0).complete(Prepared.ready(LEG));
        ticks(5);
        a.listener.stuck(a, NpcMover.StuckKind.STATIONARY);
        assertTrue(service.stuckSpellOpen(1), "no one ahead on its (empty) path: stuck");
        NavPoint side = a.follows.get(a.follows.size() - 1).get(0);
        assertTrue(side.blockX() == 4 && side.blockZ() != 5, "diagonally back instead: " + side);
    }

    @Test
    void theSameSideStepWithTheKeyOffGoesStraightBack() {
        world.addWall(6, 64, 5);
        FakeMover a = npc(1, feet(5.5, 5.5), false);
        npc(2, feet(4.5, 5.5), false);
        assertTrue(service.navigate(a.id(), feet(14.5, 3.5), results::add));
        service.tick();
        legAsks.get(0).complete(Prepared.ready(LEG));
        ticks(5);
        a.listener.stuck(a, NpcMover.StuckKind.STATIONARY);
        NavPoint side = a.follows.get(a.follows.size() - 1).get(0);
        assertEquals(4, side.blockX());
        assertEquals(5, side.blockZ(), "the key off: the cell is taken anyway");
    }
}
