package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationServiceTest.FakeMover;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.GateCrossing;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * Rules stay binding while NPCs walk (implementation plan Phase 3; NAV-02, 03, 04, 13): the recheck of the way
 * ahead, gate and availability events, the walk to the blocking element and the wait there, the trespass guard.
 * Against a fake mover, road planner, world and rules.
 */
class NpcNavigationRulesTest {

    /** The current rules: regions nobody may enter (unless already inside) and gate doors that are closed. */
    static final class FakeRules implements AccessRules {
        final Set<String> deniedRegions = new HashSet<>();
        final Set<Integer> closedGates = new HashSet<>();
        int entryAsks;

        @Override
        public Predicate<String> mayEnter(AccessSubject subject) {
            entryAsks++;
            Set<String> current = subject.currentRegions();
            return region -> current.contains(region) || !deniedRegions.contains(region);
        }

        @Override
        public boolean gateBlocks(AccessSubject subject, int doorId) {
            return closedGates.contains(doorId);
        }
    }

    record RoadAsk(AccessSubject subject, NavPoint from, CompletableFuture<RoadPlan> answer) {
    }

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -1000, 0, -1000, 1000, 255, 1000, true);
    private static final NavPoint START = new NavPoint(WORLD, 0.5, 65, 0.5);
    private static final NavPoint FAR = new NavPoint(WORLD, 100.5, 65, 0.5);
    private static final int RECHECK = Settings.defaults().recheckTicks();

    private final FakeNavWorld world = new FakeNavWorld();
    private final FakeRules rules = new FakeRules();
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final List<RoadAsk> roadAsks = new ArrayList<>();
    private final List<CompletableFuture<Prepared>> legAsks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<NavigationResult> results = new ArrayList<>();
    private NpcNavigationService service;
    private FakeMover mover;

    @BeforeEach
    void setUp() {
        metrics.setEnabled(true);
        service = service(NpcNavigationService.DEFAULT_RULE_CHECKS_PER_TICK);
        mover = register(1, START, Settings.defaults());
    }

    private NpcNavigationService service(int ruleChecksPerTick) {
        RoadPlanner roads = (subject, from, to) -> {
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(new RoadAsk(subject, from, answer));
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> {
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(answer);
            return answer;
        };
        return new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, null, log::add, rules),
            16, ruleChecksPerTick);
    }

    private FakeMover register(int id, NavPoint feet, Settings settings) {
        FakeMover m = new FakeMover(id, feet);
        service.register(m, NpcAccessProfile.DEFAULT, settings);
        return m;
    }

    private static Settings settings(int recheckTicks, boolean stopOnTrespass) {
        Settings d = Settings.defaults();
        // no watchdog in these tests: the fake mover is moved by hand
        return new Settings(d.stretchBlocks(), d.hopBlocks(), 1200, d.maxRetries(), d.noRouteWaitTicks(),
            d.walkOnlyBlocks(), recheckTicks, stopOnTrespass);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            service.tick();
        }
    }

    private RoadAsk lastRoadAsk() {
        return roadAsks.get(roadAsks.size() - 1);
    }

    private long asksOf(FakeMover m) {
        return roadAsks.stream().filter(ask -> ask.subject().name().equals(m.name())).count();
    }

    private static RoadPlan road(double fromX, double toX, GateCrossing... gates) {
        return RoadPlan.found(List.of(floor(fromX, 64, 0.5), floor(toX, 64, 0.5)), List.of(gates));
    }

    private static GateCrossing gateAt(int doorId, double x) {
        return new GateCrossing(doorId, floor(x, 64, 0.5));
    }

    /** Regions by x: each entry is {@code name, fromX, toX} (toX exclusive). */
    private void regions(Object... nameFromTo) {
        world.regionsByColumn = (x, z) -> {
            Set<String> at = new HashSet<>();
            for (int i = 0; i < nameFromTo.length; i += 3) {
                if (x >= (int) nameFromTo[i + 1] && x < (int) nameFromTo[i + 2]) {
                    at.add((String) nameFromTo[i]);
                }
            }
            return at;
        };
    }

    /** The mover of a fresh order on the road from x 0 to 100, walking (the watchdog off). */
    private FakeMover walking(int id, Settings settings, GateCrossing... gates) {
        FakeMover m = id == 1 && settings == null ? mover : register(id, START, settings == null ? settings(RECHECK, false) : settings);
        service.navigate(m.id(), FAR, id == 1 ? results::add : result -> { });
        service.tick();
        lastRoadAsk().answer().complete(road(0.5, 100.5, gates));
        assertEquals(NpcNavState.MOVING, service.state(m.id()));
        return m;
    }

    private void at(FakeMover m, double x) {
        m.position = new NavPoint(WORLD, x, 65, 0.5);
    }

    // ==================== the recheck of the way ahead ====================

    @Test
    void theRecheckFindsANewlyDeniedRegionAheadAndIgnoresRegionsBehind() {
        regions("passed", 10, 20, "district", 50, 60);
        FakeMover m = walking(2, null);
        at(m, 30);
        ticks(RECHECK);
        assertTrue(metrics.total(Event.RULE_CHECK) >= 1, "the way ahead was checked");
        assertEquals(1, asksOf(m), "nothing is denied: no replan");

        rules.deniedRegions.add("passed");
        ticks(RECHECK);
        assertEquals(1, asksOf(m), "a denied region behind the NPC is not on its way");
        assertEquals(0, metrics.total(Event.REPLAN_RECHECK));

        rules.deniedRegions.add("district");
        ticks(RECHECK + 1);
        assertEquals(2, asksOf(m), "a denied region ahead: the route is planned again, from where the NPC stands");
        assertEquals(floor(30, 64, 0.5).blockX(), lastRoadAsk().from().blockX());
        assertEquals(1, metrics.total(Event.REPLAN_RECHECK));
        assertEquals(0, metrics.total(Event.REPLAN_EVENT));
        assertEquals(1, m.stops, "the NPC stops walking the old route at once");
        assertTrue(log.stream().anyMatch(line -> line.contains("region district") && line.contains("recheck")), log.toString());
    }

    @Test
    void aRegionTheNpcStandsInIsNotEntered() {
        regions("district", 20, 60);
        FakeMover m = walking(2, null);
        at(m, 30);
        rules.deniedRegions.add("district");
        ticks(RECHECK * 2);
        assertEquals(1, asksOf(m), "already inside: the rule about entering does not turn it around");
    }

    @Test
    void theRecheckFindsAClosedGateAheadAndIgnoresOneAlreadyPassed() {
        FakeMover m = walking(2, null, gateAt(5, 10.5), gateAt(7, 60.5));
        at(m, 30);
        rules.closedGates.add(5);
        ticks(RECHECK);
        assertEquals(1, asksOf(m), "the gate behind the NPC closed: not its way any more");

        rules.closedGates.add(7);
        ticks(RECHECK + 1);
        assertEquals(2, asksOf(m));
        assertEquals(1, metrics.total(Event.REPLAN_RECHECK));
        assertTrue(log.stream().anyMatch(line -> line.contains("gate 7")), log.toString());
    }

    @Test
    void theRecheckLooksAtTheEnginesPathOnALocalLeg() {
        regions("garden", 6, 9);
        service.navigate(mover.id(), new NavPoint(WORLD, 10.5, 65, 0.5), results::add); // a short trip: one local leg
        service.tick();
        legAsks.get(0).complete(Prepared.ready(LEG));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));
        mover.pathAhead = List.of(new NavPoint(WORLD, 3.5, 65, 0.5), new NavPoint(WORLD, 7.5, 65, 0.5));
        world.regionsByColumn = (x, z) -> x >= 6 && x < 9 && z == 0 ? Set.of("garden") : Set.of(); // not at the target
        rules.deniedRegions.add("garden");

        ticks(RECHECK + 1);

        assertEquals(1, metrics.total(Event.REPLAN_RECHECK), "the engine's path crosses a region now denied");
        assertEquals(2, legAsks.size(), "the leg is prepared again, under the rules as they are now");
        assertTrue(roadAsks.isEmpty());
    }

    @Test
    void withTheRecheckOffTheWayAheadIsNotLookedAt() {
        regions("district", 50, 60);
        FakeMover m = walking(2, settings(0, false));
        rules.deniedRegions.add("district");
        ticks(200);
        assertEquals(1, asksOf(m));
        assertEquals(0, metrics.total(Event.REPLAN_RECHECK));
        assertTrue(metrics.total(Event.RULE_CHECK) >= 4, "the trespass guard still looks");
    }

    @Test
    void ruleChecksAreSpreadOverTicksNeverAllAtOnce() {
        service = service(2);
        List<FakeMover> movers = new ArrayList<>();
        for (int id = 10; id < 15; id++) {
            FakeMover m = register(id, START, settings(RECHECK, false));
            service.navigate(m.id(), FAR, result -> { });
            movers.add(m);
        }
        service.tick();
        roadAsks.forEach(ask -> ask.answer().complete(road(0.5, 100.5)));
        ticks(RECHECK - 2);
        assertEquals(0, metrics.total(Event.RULE_CHECK));
        service.tick();
        assertEquals(2, metrics.total(Event.RULE_CHECK), "two per tick");
        service.tick();
        assertEquals(4, metrics.total(Event.RULE_CHECK));
        service.tick();
        assertEquals(5, metrics.total(Event.RULE_CHECK), "everyone had a turn");
        service.tick();
        assertEquals(5, metrics.total(Event.RULE_CHECK), "and nobody twice within recheck-ticks");
    }

    @Test
    void whenTheRouterStillRoutesThroughWhatTheRecheckSawTheNpcIsNotTurnedAroundForEver() {
        regions("district", 50, 60);
        FakeMover m = walking(2, null);
        rules.deniedRegions.add("district");
        ticks(RECHECK + 1);
        assertEquals(2, asksOf(m));
        lastRoadAsk().answer().complete(road(0.5, 100.5)); // the router (the authority) sees no block

        ticks(RECHECK * 3);
        assertEquals(2, asksOf(m), "not asked again for the same element");
        assertEquals(1, metrics.total(Event.REPLAN_RECHECK));
        assertTrue(log.stream().anyMatch(line -> line.contains("the router still routes that way")), log.toString());

        service.onAvailabilityChanged(); // the rules changed: ask again
        ticks(2);
        assertEquals(3, asksOf(m));
    }

    // ==================== gate and availability events ====================

    @Test
    void aGateEventReplansOnlyTheNpcsWhoseRouteUsesThatGate() {
        FakeMover through = walking(2, null, gateAt(7, 60.5));
        FakeMover other = walking(3, null, gateAt(8, 60.5));
        FakeMover none = walking(4, null);
        FakeMover past = walking(5, null, gateAt(7, 10.5));
        at(past, 40);
        long checksBefore = metrics.total(Event.RULE_CHECK);

        rules.closedGates.add(7);
        service.onGateChanged(7);
        ticks(2);

        assertEquals(2, asksOf(through), "its route passes the gate that closed");
        assertEquals(1, asksOf(other));
        assertEquals(1, asksOf(none));
        assertEquals(1, asksOf(past), "the gate is behind it");
        assertEquals(1, metrics.total(Event.REPLAN_EVENT));
        assertEquals(0, metrics.total(Event.REPLAN_RECHECK));
        assertEquals(2, metrics.total(Event.RULE_CHECK) - checksBefore, "only the two NPCs with that gate on their route were looked at");
    }

    @Test
    void aGateEventThatLeavesTheGateOpenReplansNobody() {
        FakeMover through = walking(2, null, gateAt(7, 60.5));
        service.onGateChanged(7); // it opened wider, say
        ticks(2);
        assertEquals(1, asksOf(through));
        assertEquals(0, metrics.total(Event.REPLAN_EVENT));
    }

    @Test
    void anAvailabilityEventReplansOnlyNpcsWithSomethingDeniedAhead() {
        regions("district", 50, 60);
        FakeMover into = walking(2, null);
        FakeMover before = register(3, START, settings(RECHECK, false));
        service.navigate(before.id(), new NavPoint(WORLD, 40.5, 65, 0.5), result -> { });
        service.tick();
        lastRoadAsk().answer().complete(road(0.5, 40.5));

        service.onAvailabilityChanged();
        ticks(2);
        assertEquals(1, asksOf(into), "nothing is denied: the refresh replans nobody");
        assertEquals(1, asksOf(before));
        assertEquals(2, metrics.total(Event.RULE_CHECK), "but both were rechecked at once, not after recheck-ticks");

        rules.deniedRegions.add("district");
        service.onAvailabilityChanged();
        ticks(2);
        assertEquals(2, asksOf(into), "its route enters the district");
        assertEquals(1, asksOf(before), "its route ends before the district");
        assertEquals(1, metrics.total(Event.REPLAN_EVENT));
    }

    // ==================== the walk to the blocking element and the wait there ====================

    private RoadPlan blockedAtGate(double fromX, double toX) {
        return RoadPlan.blocked(List.of(floor(fromX, 64, 0.5), floor(toX, 64, 0.5)), "the West Gate is closed",
            BlockingElement.gate(7, "the West Gate"), List.of(), subject -> rules.closedGates.contains(7));
    }

    private void walkToTheEndOfTheRoute(FakeMover m) {
        int guard = 0;
        while (service.state(m.id()) == NpcNavState.MOVING && guard++ < 20) {
            m.arrive();
        }
    }

    @Test
    void aBlockedNpcWalksToTheGateWaitsThereAndGoesOnWhenTheGateOpens() {
        rules.closedGates.add(7);
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(blockedAtGate(0.5, 40.5));

        NavigationResult.Blocked blocked = assertInstanceOf(NavigationResult.Blocked.class, results.get(0));
        assertEquals(BlockingElement.Kind.GATE, blocked.blockingElement().kind());
        assertEquals("7", blocked.blockingElement().id());
        assertEquals("the West Gate", blocked.blockingElement().name());
        assertEquals(NpcNavState.MOVING, service.state(mover.id()), "it does not stop in its tracks");
        assertEquals(1, metrics.total(Event.BLOCKED_WALK));
        assertEquals(BlockingElement.gate(7, "the West Gate"), service.blockingOf(mover.id()));
        assertFalse(service.waitsAtBlock(mover.id()));

        walkToTheEndOfTheRoute(mover);
        assertEquals(NpcNavState.WAITING, service.state(mover.id()));
        assertTrue(service.waitsAtBlock(mover.id()));
        assertEquals(40.5, mover.position.x(), 1.0, "at the gate");
        assertEquals(1, results.size(), "still the same order");

        ticks(Settings.defaults().noRouteWaitTicks() - 10);
        assertEquals(1, roadAsks.size(), "waiting costs no plans");

        rules.closedGates.remove(7);
        service.onGateChanged(7);
        service.tick();
        assertEquals(2, roadAsks.size(), "the open event wakes it, not the timer");
        assertEquals(1, metrics.total(Event.WOKEN));
        assertEquals(1, metrics.total(Event.REPLAN_EVENT));
        assertFalse(service.waitsAtBlock(mover.id()));

        lastRoadAsk().answer().complete(road(40.5, 100.5));
        assertNull(service.blockingOf(mover.id()));
        walkToTheEndOfTheRoute(mover);
        assertInstanceOf(NavigationResult.Arrived.class, results.get(results.size() - 1));
        assertEquals(2, results.size(), "Blocked once, then Arrived");
    }

    @Test
    void anNpcAtAGateThatIsStillClosedIsNotWoken() {
        rules.closedGates.add(7);
        rules.closedGates.add(9);
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(blockedAtGate(0.5, 40.5));
        walkToTheEndOfTheRoute(mover);

        service.onGateChanged(7); // jammed, still closed
        service.onGateChanged(9); // another gate, closed too
        service.onAvailabilityChanged();
        ticks(3);
        assertEquals(1, roadAsks.size());
        assertEquals(0, metrics.total(Event.WOKEN));

        rules.closedGates.remove(9);
        service.onGateChanged(9);
        ticks(2);
        assertEquals(2, roadAsks.size(), "another gate opened: there may be another way now");
    }

    @Test
    void theSafetyTimerAsksAgainAndAnNpcAlreadyAtTheGateStaysThere() {
        rules.closedGates.add(7);
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(blockedAtGate(0.5, 40.5));
        walkToTheEndOfTheRoute(mover);
        int follows = mover.follows.size();

        ticks(Settings.defaults().noRouteWaitTicks() + 1);
        assertEquals(2, roadAsks.size(), "the fallback when an event was missed");
        lastRoadAsk().answer().complete(blockedAtGate(mover.position.x(), 40.5));

        assertTrue(service.waitsAtBlock(mover.id()));
        assertEquals(follows, mover.follows.size(), "nothing to walk");
        assertEquals(1, results.size(), "the same element is not reported again");
        assertEquals(1, metrics.total(Event.BLOCKED_WALK));
    }

    @Test
    void anotherBlockingElementIsReportedAgain() {
        rules.closedGates.add(7);
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(blockedAtGate(0.5, 40.5));
        walkToTheEndOfTheRoute(mover);
        ticks(Settings.defaults().noRouteWaitTicks() + 1);
        BlockingElement keep = new BlockingElement(BlockingElement.Kind.DOMAIN, "12", "Kardenna Castle", Set.of(),
            Set.of("keep"));
        lastRoadAsk().answer().complete(RoadPlan.blocked(List.of(), "you may not enter Kardenna Castle", keep, List.of(), null));

        assertEquals(2, results.size());
        assertEquals(keep, ((NavigationResult.Blocked) results.get(1)).blockingElement());
    }

    @Test
    void anNpcOnItsWayToAGateThatOpensPlansTheFullRouteAtOnce() {
        rules.closedGates.add(7);
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(blockedAtGate(0.5, 40.5));
        assertEquals(NpcNavState.MOVING, service.state(mover.id()));

        rules.closedGates.remove(7);
        service.onGateChanged(7);
        ticks(2);

        assertEquals(2, roadAsks.size());
        assertEquals(1, metrics.total(Event.REPLAN_EVENT));
        assertEquals(0, metrics.total(Event.WOKEN), "it was not waiting yet");
    }

    @Test
    void thePartialRouteEndsBeforeTheDeniedDistrict() {
        regions("district", 30, 60);
        rules.deniedRegions.add("district");
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        // the router's partial route ends at a road node inside the district's edge
        BlockingElement district = new BlockingElement(BlockingElement.Kind.DOMAIN, "3", "the Old District", Set.of(),
            Set.of("district"));
        lastRoadAsk().answer().complete(RoadPlan.blocked(List.of(floor(0.5, 64, 0.5), floor(36.5, 64, 0.5)),
            "you may not enter the Old District", district, List.of(), subject -> rules.deniedRegions.contains("district")));

        List<NavPoint> line = service.followerOf(mover.id()).line();
        assertTrue(line.get(line.size() - 1).x() < 30, "cut at the district's edge: " + line.get(line.size() - 1));
        assertTrue(line.get(line.size() - 1).x() > 26, "and not earlier");
        walkToTheEndOfTheRoute(mover);
        assertTrue(service.waitsAtBlock(mover.id()));
        ticks(RECHECK * 2);
        assertEquals(0, metrics.total(Event.TRESPASS));
        assertEquals(1, roadAsks.size(), "the recheck has nothing to object to on the way there");

        rules.deniedRegions.remove("district");
        service.onAvailabilityChanged();
        ticks(2);
        assertEquals(2, roadAsks.size(), "the access change wakes it");
        assertEquals(1, metrics.total(Event.WOKEN));
    }

    @Test
    void anNpcWithNothingAllowedAheadWaitsWhereItIs() {
        regions("district", 2, 60);
        rules.deniedRegions.add("district");
        service.navigate(mover.id(), FAR, results::add);
        service.tick();
        lastRoadAsk().answer().complete(RoadPlan.blocked(List.of(floor(0.5, 64, 0.5), floor(36.5, 64, 0.5)),
            "you may not enter the Old District", null, List.of(), null));

        assertTrue(service.waitsAtBlock(mover.id()));
        assertTrue(mover.follows.isEmpty() && mover.walks.isEmpty());
        assertNotNull(service.blockingOf(mover.id()), "an unnamed block still has an element for behavior");
        assertEquals(BlockingElement.Kind.UNKNOWN, service.blockingOf(mover.id()).kind());
    }

    // ==================== the trespass guard ====================

    @Test
    void anNpcThatEntersADeniedRegionIsCountedAndLoggedOnce() {
        regions("keep", 20, 60);
        rules.deniedRegions.add("keep");
        FakeMover m = walking(2, settings(0, false)); // the recheck off, so nothing turns it around first
        ticks(NpcNavigationService.TRESPASS_TICKS);
        assertEquals(0, metrics.total(Event.TRESPASS), "outside");

        at(m, 25);
        ticks(NpcNavigationService.TRESPASS_TICKS);
        assertEquals(1, metrics.total(Event.TRESPASS));
        assertTrue(log.stream().anyMatch(line -> line.startsWith("TRESPASS npc-2 entered keep at 25 65 0")), log.toString());
        assertEquals(1, asksOf(m), "counted, not stopped (stop-on-trespass is off)");

        at(m, 40);
        ticks(NpcNavigationService.TRESPASS_TICKS * 2);
        assertEquals(1, metrics.total(Event.TRESPASS), "an entry is counted once, not every check while inside");
    }

    @Test
    void anAllowedRegionIsNoTrespass() {
        regions("market", 20, 60);
        FakeMover m = walking(2, settings(0, false));
        ticks(NpcNavigationService.TRESPASS_TICKS);
        at(m, 25);
        ticks(NpcNavigationService.TRESPASS_TICKS);
        assertEquals(0, metrics.total(Event.TRESPASS));
    }

    @Test
    void withStopOnTrespassTheNpcIsStoppedAndPlansAgain() {
        regions("keep", 20, 60);
        rules.deniedRegions.add("keep");
        FakeMover m = walking(2, settings(0, true));
        ticks(NpcNavigationService.TRESPASS_TICKS);
        at(m, 25);
        ticks(NpcNavigationService.TRESPASS_TICKS + 1);
        assertEquals(1, metrics.total(Event.TRESPASS));
        assertEquals(2, asksOf(m));
        assertEquals(1, m.stops);
    }

    // ==================== settings ====================

    @Test
    void theSettingsCarryTheRecheckIntervalAndTheTrespassSwitch() {
        assertEquals(40, Settings.defaults().recheckTicks());
        assertFalse(Settings.defaults().stopOnTrespass());
        List<String> rejected = new ArrayList<>();
        NavigationSettings layered = NavigationSettings.builder()
            .global(NavigationSettings.Overrides.parse(java.util.Map.of("recheck-ticks", 20, "stop-on-trespass", true),
                "npc.navigation", rejected))
            .build();
        Settings settings = Settings.of(layered, NavigationSettings.Scope.of(WORLD));
        assertEquals(List.of(), rejected);
        assertEquals(20, settings.recheckTicks());
        assertTrue(settings.stopOnTrespass());
    }
}
