package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcMover.Contact;
import net.knightsandkings.knk.core.npc.nav.NpcMover.ContactKind;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * Push resistance in the navigation service (implementation plan 4c.4 item 7.2, link M7): with collision off nothing
 * changes; with it on the NPC is put under the collision policy, a push is counted and logged with its pusher, and the
 * NPC returns in the plan's order (straight back where that is a straight walk, else a local leg, last a teleport home
 * out of every player's sight); a walking NPC jostled by another NPC walks on; a player who keeps pushing, or pushes it
 * towards a drop, is exempted for a while.
 */
class NpcNavigationPushTest {

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);

    /** What the service asked of the collision policy. */
    private static final class FakePolicy implements NpcCollisionPolicy {
        final List<String> calls = new ArrayList<>();

        @Override
        public void enroll(NpcMover mover, boolean playersPush) {
            calls.add("enroll " + mover.id() + (playersPush ? " players push" : " players never"));
        }

        @Override
        public void release(int moverId) {
            calls.add("release " + moverId);
        }

        @Override
        public void exempt(int moverId, String playerId, boolean exempt) {
            calls.add((exempt ? "exempt " : "restore ") + moverId + " " + playerId);
        }
    }

    private final FakeNavWorld world = new FakeNavWorld();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<CompletableFuture<RoadPlan>> roadAsks = new ArrayList<>();
    private final List<NavPoint> legTargets = new ArrayList<>();
    private final List<CompletableFuture<Prepared>> legAsks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final FakePolicy policy = new FakePolicy();
    private boolean seen;
    private NpcNavigationService service;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        RoadPlanner roads = (subject, from, to) -> {
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(answer);
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legTargets.add(target);
            legAsks.add(answer);
            return answer;
        };
        service = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, System::nanoTime,
            log::add), 4);
        service.setCollision(policy, feet -> seen);
    }

    private static Settings settings(boolean collidable, boolean playersPush) {
        Settings d = Settings.defaults();
        return new Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), d.maxRetries(), d.noRouteWaitTicks(),
            d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(), d.cornerClearance(), d.waypointMargin(),
            d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(), d.wallClearance(),
            d.localLegsClearance(), d.plazaLines(), d.plazaCorridorBlocks(), d.crowdAwareness(), collidable,
            playersPush, true, 3, 200, 1200, 600);
    }

    private static NavPoint feet(double x, double z) {
        return new NavPoint(WORLD, x, 65, z);
    }

    private FakeMover register(int id, NavPoint at, Settings settings) {
        FakeMover m = new FakeMover(id, at);
        service.register(m, NpcAccessProfile.DEFAULT, settings);
        return m;
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            service.tick();
        }
    }

    /** An NPC walking a straight road east from x 0.5 at z 0.5, its first stretch handed out. */
    private FakeMover walking(int id, Settings settings) {
        FakeMover m = register(id, feet(0.5, 0.5), settings);
        service.navigate(id, feet(100.5, 0.5), r -> { });
        service.tick();
        roadAsks.get(roadAsks.size() - 1).complete(RoadPlan.found(List.of(floor(0.5, 64, 0.5),
            floor(100.5, 64, 0.5))));
        assertEquals(NpcNavState.MOVING, service.state(id));
        return m;
    }

    /** Moves a walking NPC east 0.5 blocks per 2 ticks for {@code looks} looks. */
    private void walk(FakeMover m, int looks) {
        for (int i = 0; i < looks; i++) {
            m.position = feet(m.position.x() + 0.5, 0.5);
            ticks(2);
        }
    }

    @Test
    void withCollisionOffNothingIsWatched() {
        FakeMover m = walking(1, settings(false, false));
        walk(m, 4);
        int follows = m.follows.size();
        m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
        m.position = feet(m.position.x() - 1.5, 2.5);
        ticks(10);
        assertTrue(policy.calls.isEmpty(), policy.calls.toString());
        assertEquals(0, metrics.total(Event.PUSHED));
        assertEquals(follows, m.follows.size(), "no walk back");
    }

    @Test
    void collisionOnEnrollsTheNpcAgainAfterARespawnAndReleasesItOnUnregister() {
        FakeMover a = register(1, feet(0.5, 0.5), settings(true, false));
        register(2, feet(5.5, 0.5), settings(true, true));
        assertEquals(List.of("enroll 1 players never", "enroll 2 players push"), policy.calls);
        a.listener.respawned(a);
        assertEquals("enroll 1 players never", policy.calls.get(2), "the entity is new: enrolled again");
        service.unregister(1);
        assertEquals("release 1", policy.calls.get(3));
    }

    @Test
    void aWalkingNpcPushedOffItsLineWalksStraightBackAndOnAlongItsRoute() {
        FakeMover m = walking(1, settings(true, false));
        walk(m, 4);
        NavPoint before = m.position;
        int follows = m.follows.size();
        m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
        m.position = feet(before.x() - 0.2, 2.0); // shoved 1.5 sideways and a little back
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED));
        assertTrue(service.returning(1));
        List<NavPoint> back = m.follows.get(follows);
        assertEquals(before, back.get(0), "first straight back where it was pushed from");
        assertTrue(back.size() > 2 && back.get(back.size() - 1).x() > before.x() + 5, "then on along its route");
        assertTrue(log.stream().anyMatch(l -> l.contains("pushed 1.5 blocks") && l.contains("by player steve")),
            log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("returns straight back")), log.toString());
        m.position = before;
        ticks(2);
        assertFalse(service.returning(1));
        assertEquals(1, metrics.total(Event.PUSH_RETURNED));
        assertEquals(NpcNavState.MOVING, service.state(1));
    }

    @Test
    void withoutAStraightWayBackALocalLegReturnsItAndTheRouteGoesOnFromThere() {
        FakeMover m = walking(1, settings(true, false));
        walk(m, 4);
        NavPoint before = m.position; // x 2.5
        // a wall between where it was and where a push puts it (z 1 from x 0 to 5)
        for (int x = 0; x <= 5; x++) {
            world.addWall(x, 64, 1);
        }
        m.position = feet(2.5, 2.5);
        ticks(2);
        assertTrue(service.returning(1));
        assertTrue(log.stream().anyMatch(l -> l.contains("returns a local leg back")), log.toString());
        assertEquals(1, legAsks.size(), "a local leg through the planning queue");
        assertEquals(before.x(), legTargets.get(0).x(), 1e-9);
        assertEquals(before.z(), legTargets.get(0).z(), 1e-9);
        legAsks.get(0).complete(Prepared.ready(LEG));
        assertEquals(before, m.walks.get(m.walks.size() - 1), "the engine walks the leg home under K&K's capture");
        int follows = m.follows.size();
        m.arrive();
        assertEquals(follows + 1, m.follows.size(), "then its route from there");
        assertTrue(m.follows.get(follows).get(0).x() >= before.x() - 1, "from where it was, not from the start");
    }

    @Test
    void aWalkingNpcJostledByAnotherNpcWalksOn() {
        FakeMover m = walking(1, settings(true, false));
        walk(m, 4);
        int follows = m.follows.size();
        m.listener.contact(m, new Contact(ContactKind.NPC, "npc-2"));
        m.position = feet(m.position.x() + 0.2, 1.3);
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED), "counted");
        assertFalse(service.returning(1), "but crowds push: no walk back");
        assertEquals(follows, m.follows.size());
    }

    @Test
    void anUnreportedPushWithAnotherNpcAtItsSideIsThatNpcsJostle() {
        FakeMover m = walking(1, settings(true, false));
        walk(m, 4);
        register(2, feet(m.position.x() + 0.3, 1.6), settings(true, false));
        int follows = m.follows.size();
        m.position = feet(m.position.x() - 0.6, 0.6); // back half a block, nothing reported it
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED));
        assertFalse(service.returning(1), "the NPC beside it did it: it walks on");
        assertEquals(follows, m.follows.size());
    }

    @Test
    void aStandingNpcPushedFromItsPostWalksBackToIt() {
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, false));
        ticks(4);
        m.position = feet(6.5, 5.5);
        ticks(2);
        assertTrue(service.returning(1));
        assertEquals(List.of(feet(5.5, 5.5)), m.follows.get(m.follows.size() - 1), "a walk back to its post");
        assertEquals(NpcNavState.IDLE, service.state(1), "no order of its own for that");
        m.position = feet(5.6, 5.5);
        m.moving = false;
        ticks(2);
        assertFalse(service.returning(1));
        assertEquals(1, metrics.total(Event.PUSH_RETURNED));
    }

    @Test
    void aGroupFollowerIsLeftToItsGroup() {
        service.setGroupFollowers(id -> id == 1);
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, false));
        ticks(4);
        m.position = feet(6.5, 5.5);
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED));
        assertFalse(service.returning(1));
        assertTrue(m.follows.isEmpty());
    }

    @Test
    void aPlayerWhoKeepsPushingIsExemptedForAWhile() {
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, true));
        ticks(4);
        double x = 5.5;
        for (int push = 1; push <= 3; push++) {
            m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
            x += 1;
            m.position = feet(x, 5.5);
            ticks(40); // a new episode each time
        }
        assertTrue(policy.calls.contains("exempt 1 steve"), policy.calls.toString());
        assertEquals(1, metrics.total(Event.PUSH_EXEMPT));
        assertTrue(log.stream().anyMatch(l -> l.contains("player steve pushed it 3 times")), log.toString());
        assertEquals(1, service.pushCounts()[5], "one exemption in force");
        ticks(1200);
        assertTrue(policy.calls.contains("restore 1 steve"), policy.calls.toString());
        assertEquals(0, service.pushCounts()[5]);
    }

    @Test
    void withPlayersNeverPushingNoExemptionIsGiven() {
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, false));
        ticks(4);
        for (int push = 1; push <= 4; push++) {
            m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
            m.position = feet(5.5 + push, 5.5);
            ticks(40);
        }
        assertFalse(policy.calls.stream().anyMatch(c -> c.startsWith("exempt")), policy.calls.toString());
    }

    @Test
    void aPushTowardsADropExemptsThePlayerAtOnce() {
        // a ledge: no floor south of z 7
        world.floor = (x, z) -> z >= 8 ? 50 : 64;
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, true));
        ticks(4);
        m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
        m.position = feet(5.5, 7.4);
        ticks(2);
        assertTrue(policy.calls.contains("exempt 1 steve"), "the first push already: " + policy.calls);
        assertTrue(log.stream().anyMatch(l -> l.contains("(towards a drop)")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("pushed it towards a drop")), log.toString());
        assertTrue(service.returning(1));
    }

    @Test
    void aPushThatReachesTheEdgeLaterInTheSameEpisodeStillExemptsAtOnce() {
        world.floor = (x, z) -> z >= 8 ? 50 : 64;
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, true));
        ticks(4);
        m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
        m.position = feet(5.5, 6.1); // not next to the drop yet: one push of three
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED));
        assertFalse(policy.calls.contains("exempt 1 steve"));
        m.listener.contact(m, new Contact(ContactKind.PLAYER, "steve"));
        m.position = feet(5.5, 7.3); // the same push goes on, onto the edge cell
        ticks(2);
        assertEquals(1, metrics.total(Event.PUSHED), "still one push");
        assertTrue(policy.calls.contains("exempt 1 steve"), "exempted as soon as it goes towards the drop");
        assertTrue(service.returning(1));
    }

    @Test
    void theTeleportHomeWaitsUntilNobodySeesIt() {
        FakeMover m = walking(1, settings(true, false));
        m.canTeleport = true;
        walk(m, 4);
        NavPoint before = m.position;
        m.listener.contact(m, new Contact(ContactKind.ATTACK, "steve", true));
        m.position = feet(before.x(), 3.0);
        ticks(2);
        assertTrue(service.returning(1));
        seen = true;
        ticks(700); // the walk back never gets anywhere (the body stays put)
        assertTrue(m.teleports.isEmpty(), "seen by a player: no teleport");
        assertTrue(service.returning(1));
        seen = false;
        ticks(40);
        assertEquals(List.of(before), m.teleports, "out of sight: teleported home");
        assertFalse(service.returning(1));
        assertEquals(1, metrics.total(Event.PUSH_TELEPORT));
        assertEquals(1, service.pushCounts()[2], "an attack's knockback");
        assertEquals(1, service.pushCounts()[1], "by a player");
        assertTrue(log.stream().anyMatch(l -> l.contains("teleported home")), log.toString());
    }

    @Test
    void aReturningNpcThatIsPushedAgainStillReturnsToWhereItWasFirst() {
        FakeMover m = register(1, feet(5.5, 5.5), settings(true, false));
        ticks(4);
        m.position = feet(6.5, 5.5);
        ticks(2);
        ticks(40); // on its way back (the body stays put here), then pushed again in a new episode
        m.position = feet(6.5, 7.5);
        ticks(2);
        assertEquals(feet(5.5, 5.5), m.follows.get(m.follows.size() - 1).get(0));
        assertEquals(2, metrics.total(Event.PUSHED));
    }
}
