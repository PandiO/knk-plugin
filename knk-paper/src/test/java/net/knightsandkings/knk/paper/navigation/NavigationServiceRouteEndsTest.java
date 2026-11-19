package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;

/**
 * Fix link F2 (PandiO/knk-workspace#780 option (b), #771 items 4 and 5) through the runtime, on the developer's dev world:
 * the keep snapshots' blocks and road graph, the real walk search with the shipped {@code walk:} limits and captures of
 * the leg's box plus {@code capture-margin} 16, at most {@code max-chunks-per-request} 64 chunks. Routing and the main
 * thread run inline; walk searches wait for {@link #runSearches}.
 */
class NavigationServiceRouteEndsTest {

    /** Location 56, "Keep storage hall" (feet). */
    static final double[] HALL = {1421.5, 73, -487.5};
    /** Location 52, "Keep Tower Roof" (feet). */
    static final double[] TOWER = {1411.5, 113, -503.5};
    /** On the road from spawn (1443 43 -567) towards the keep, inside the snapshots' box (feet). */
    static final double[] ON_THE_ROAD = {1442.5, 43, -552.5};
    /** Today's road goal of the hall: the keep stair house's lower landing, a dead end (#780). */
    static final int STAIR_HOUSE_LANDING = 2000000033;
    /** Today's road goal of the tower roof: about 10 blocks past where the walk to the stair leaves the road (#771 item 4). */
    static final int PAST_THE_STAIR = 2000000019;

    private static DevWorldSnapshot keep;
    private static DevWorldSnapshot tower;

    private DevWorldSnapshot dev;
    private final AtomicLong tick = new AtomicLong(100);
    private final List<Event> events = new ArrayList<>();
    private final NavigationHud hud = mock(NavigationHud.class);
    private final TrailRenderer trail = mock(TrailRenderer.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private Location feet;
    private final List<Runnable> walkSearches = new ArrayList<>();
    private final List<double[]> walkStarts = new ArrayList<>();
    private NavigationService service;

    @BeforeAll
    static void loadSnapshots() throws Exception {
        keep = DevWorldSnapshot.load("/scenario-snapshots/quarry-to-keep-stairhouse.zip");
        tower = DevWorldSnapshot.load("/scenario-snapshots/keep-tower.zip");
    }

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(DevWorldSnapshot.WORLD);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Pandi");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> feet.clone());
        when(trail.periodTicks()).thenReturn(10);
        moveTo(ON_THE_ROAD);
    }

    private static NavigationConfig keysOn(boolean reachableGoal, boolean roadToItsEnd) {
        return NavigationConfig.defaults().withRouteEnds(new NavigationConfig.RouteEndsConfig(reachableGoal,
            NavigationConfig.RouteEndsConfig.DEFAULT_GOAL_CANDIDATES, roadToItsEnd));
    }

    private NavigationService service(DevWorldSnapshot snapshot, NavigationConfig config) {
        dev = snapshot;
        NavigationService.WalkPreparer preparer = (p, from, target, goal, destinationRegions) -> {
            walkStarts.add(from.clone());
            WalkBox box = WalkBox.around(DevWorldSnapshot.WORLD, from[0], from[1], from[2], target[0], target[1], target[2],
                config.walk().captureMargin(), -64, 320);
            if (box.chunkCount() > config.walk().maxChunksPerRequest()) {
                return CompletableFuture.completedFuture(null); // too large: not captured, as the server says
            }
            var terrain = snapshot.capture(box).terrain();
            Supplier<WalkRequest> request = () -> new WalkRequest(terrain, CellAccess.OPEN, MovementProfile.PLAYER,
                from[0], from[1], from[2], target[0], target[1], target[2], goal, config.walk().budget());
            return CompletableFuture.completedFuture(request);
        };
        NavigationService.PolicyFactory policies = new NavigationService.PolicyFactory() {
            @Override
            public AccessPolicy policyFor(Player pl, RoadNetworkSnapshot s) {
                return AccessPolicy.ALL_OPEN;
            }

            @Override
            public AccessPolicy policyFor(net.knightsandkings.knk.core.npc.nav.AccessSubject subject, RoadNetworkSnapshot s,
                                          java.util.Set<String> destinationRegions) {
                return AccessPolicy.ALL_OPEN;
            }
        };
        WalkSearch search = new WalkSearch();
        service = new NavigationService(new NavigationService.Deps(null, config, w -> snapshot.network, policies,
            (w, id) -> Optional.empty(), new NavigationEligibility(u -> false, u -> false, u -> false), hud, trail,
            Runnable::run, Runnable::run, tick::get, events::add, Logger.getLogger("test"),
            new NavigationService.Walk(preparer, search::find, walkSearches::add)));
        return service;
    }

    private void runSearches() {
        while (!walkSearches.isEmpty()) {
            List<Runnable> queued = new ArrayList<>(walkSearches);
            walkSearches.clear();
            queued.forEach(Runnable::run);
        }
    }

    private void moveTo(double[] p) {
        feet = new Location(world, p[0], p[1], p[2]);
    }

    private Route route() {
        return service.sessionOf(playerId).orElseThrow().route().orElseThrow();
    }

    private Route navigateTo(String name, double[] target) {
        service.navigate(player, Destination.point(name, DevWorldSnapshot.WORLD, target[0], target[1], target[2]));
        runSearches();
        return route();
    }

    /** The walk the last leg would search from the route's end (the player stands there), with the shipped limits. */
    private WalkResult walkFromTheEnd(Route route, double[] target) {
        double[] end = route.end().point();
        double[] from = {Math.floor(end[0]) + 0.5, Math.floor(end[1]) + 1, Math.floor(end[2]) + 0.5};
        NavigationConfig config = NavigationConfig.defaults();
        WalkBox box = WalkBox.around(DevWorldSnapshot.WORLD, from[0], from[1], from[2], target[0], target[1] - 1, target[2],
            config.walk().captureMargin(), -64, 320);
        return new WalkSearch().find(WalkRequest.toPoint(dev.capture(box).terrain(), from[0], from[1], from[2], target[0],
            target[1] - 1, target[2], 4).withBudget(config.walk().budget()));
    }

    // ===== #780 (b): the keep storage hall =====

    @Test
    void withTheKeyOffTheHallsRoadGoalIsTodaysDeadEnd() {
        service(keep, NavigationConfig.defaults());
        Route route = navigateTo("Keep storage hall", HALL);

        assertEquals(STAIR_HOUSE_LANDING, route.end().edgeId(), route.end().toString());
        assertEquals(1415, route.end().x(), 1);
        assertEquals(58, route.end().y(), 1);
        assertEquals(-490, route.end().z(), 1);
        WalkResult walk = walkFromTheEnd(route, HALL);
        assertNotEquals(WalkResult.Status.FOUND, walk.status(), "#780: no walk within the shipped limits: " + walk);
    }

    @Test
    void withTheKeyOnTheHallsRoadGoalIsOneTheHallIsReachedFrom() {
        service(keep, keysOn(true, false));
        service.navigate(player, Destination.point("Keep storage hall", DevWorldSnapshot.WORLD, HALL[0], HALL[1], HALL[2]));
        assertEquals(STAIR_HOUSE_LANDING, route().end().edgeId(), "the first route is today's");

        runSearches();
        Route route = route();
        assertNotEquals(STAIR_HOUSE_LANDING, route.end().edgeId(), route.end().toString());
        assertTrue(route.end().y() >= 80, "in the keep's courtyard, above the hall: " + route.end());
        double[] end = route.end().point();
        assertTrue(NavigationService.distance(end[0], end[1], end[2], new double[] {HALL[0], HALL[1] - 1, HALL[2]})
            <= NavigationConfig.defaults().maxSnapDistance(), "within max-snap-distance of the hall");
        WalkResult walk = walkFromTheEnd(route, HALL);
        assertEquals(WalkResult.Status.FOUND, walk.status(), walk.toString());
        assertTrue(walk.path().orElseThrow().length() < 150, "the shipped max-length: " + walk);
    }

    @Test
    void theLastLegFromTheChosenGoalStartsWithItsWalkPathAndReachesTheHall() {
        service(keep, keysOn(true, false));
        Route route = navigateTo("Keep storage hall", HALL);
        double[] end = route.end().point();
        moveTo(new double[] {Math.floor(end[0]) + 0.5, end[1] + 1, Math.floor(end[2]) + 0.5});
        for (int i = 0; i < 3 && !service.isDirect(playerId); i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
        assertTrue(service.isDirect(playerId), "the last walk leg from the road's end");
        runSearches();
        NavigationService.DirectLeg leg = service.legOf(playerId).orElseThrow();
        assertTrue(leg.walking(), "a found walk path, not a fallback: " + leg.status);
        assertFalse(leg.partial);
    }

    // ===== #771 item 4: scene 2, the road part overshoots the stair =====

    @Test
    void scene2WithTheKeyOnTheRoadNoLongerRunsPastTheWalksStart() {
        service(tower, NavigationConfig.defaults());
        Route today = navigateTo("Keep Tower Roof", TOWER);
        assertEquals(PAST_THE_STAIR, today.end().edgeId(), today.end().toString());
        WalkResult todaysWalk = walkFromTheEnd(today, TOWER);
        assertEquals(WalkResult.Status.FOUND, todaysWalk.status(), todaysWalk.toString());

        service.stop(player);
        service(tower, keysOn(true, false));
        Route chosen = navigateTo("Keep Tower Roof", TOWER);
        WalkResult walk = walkFromTheEnd(chosen, TOWER);
        assertEquals(WalkResult.Status.FOUND, walk.status(), walk.toString());
        assertTrue(chosen.length() <= today.length() - 5, "no road overshoot: " + chosen.length() + " vs " + today.length());
        assertTrue(chosen.length() + walk.path().orElseThrow().length()
            < today.length() + todaysWalk.path().orElseThrow().length(), "less road plus walk");
    }

    // ===== #771 item 5: a network swap inside max-snap-distance =====

    private void networkSwapInsideMaxSnap(NavigationConfig config) {
        service(keep, config);
        navigateTo("Keep storage hall", HALL);
        Route route = route();
        double[] near = route.pointAt(100); // in the stair house, about 27 blocks from the hall
        assertTrue(NavigationService.distance(near[0], near[1], near[2], new double[] {HALL[0], HALL[1] - 1, HALL[2]}) < 48);
        moveTo(new double[] {near[0] + 0.5, near[1] + 1, near[2] + 0.5});
        tick.incrementAndGet();
        service.tickAll();
        service.onNetworkChanged(DevWorldSnapshot.WORLD); // live edge tags: the same roads, a new snapshot
    }

    @Test
    void withTheKeyOffANetworkSwapInsideMaxSnapSwitchesToDirectModeAsToday() {
        networkSwapInsideMaxSnap(NavigationConfig.defaults());
        // today: a walk leg from where the player stands, mid-road (here it finds no way within the walk limits, so the
        // roads are asked again afterwards: tryRoadsInstead)
        assertTrue(service.isDirect(playerId), "a walk leg from mid-road at " + feet);
    }

    @Test
    void withRoadToItsEndTheRoadRouteIsKeptToItsEnd() {
        networkSwapInsideMaxSnap(keysOn(false, true));
        assertFalse(service.isDirect(playerId));
        runSearches();
        assertFalse(service.isDirect(playerId));
        assertEquals(STAIR_HOUSE_LANDING, route().end().edgeId(), "the same road goal on the new network");
    }

    // ===== flag-off identity and NPC plans =====

    /** Every trail and HUD call with its arguments, the walk searches' starts and the events, in order. */
    private List<String> transcript() {
        List<String> lines = new ArrayList<>();
        for (Object m : List.of(trail, hud, player)) {
            mockingDetails(m).getInvocations().forEach(i -> {
                if (i.getMethod().getName().startsWith("get") || i.getMethod().getName().startsWith("is")) {
                    return;
                }
                StringBuilder sb = new StringBuilder(i.getMethod().getName());
                for (Object arg : i.getArguments()) {
                    sb.append(' ').append(arg instanceof List<?> list ? Arrays.deepToString(list.toArray())
                        : arg instanceof Route r ? r + " " + r.length() : Arrays.deepToString(new Object[] {arg}));
                }
                lines.add(sb.toString());
            });
        }
        walkStarts.forEach(s -> lines.add("walk from " + Arrays.toString(s)));
        events.forEach(e -> lines.add(e.getEventName()));
        return lines;
    }

    private List<String> sceneTranscript(NavigationConfig config) {
        org.mockito.Mockito.clearInvocations(trail, hud, player);
        events.clear();
        walkStarts.clear();
        tick.set(100);
        moveTo(ON_THE_ROAD);
        service(keep, config);
        navigateTo("Keep storage hall", HALL);
        for (int i = 0; i < 40; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
        double[] p = route().pointAt(60);
        moveTo(new double[] {p[0] + 0.5, p[1] + 1, p[2] + 0.5});
        for (int i = 0; i < NavigationService.RECHECK_TICKS; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
        runSearches();
        return transcript();
    }

    @Test
    void withTheKeysOffEverythingIsAsToday() {
        List<String> today = sceneTranscript(NavigationConfig.defaults());
        List<String> keysOffOtherCount = sceneTranscript(NavigationConfig.defaults().withRouteEnds(
            new NavigationConfig.RouteEndsConfig(false, 3, false)));
        assertEquals(today, keysOffOtherCount, "road-goal-reachable and road-to-its-end false = before F2");
        assertTrue(today.stream().anyMatch(l -> l.startsWith("drawRoute")), today.toString());
    }

    @Test
    void anNpcRoadPlanKeepsTodaysEnds() {
        // NPC plans (planRoadFor, the NPC module's port) never choose a road goal by walk reachability
        RoadPlanner.RoadPlan[] plans = new RoadPlanner.RoadPlan[2];
        int k = 0;
        for (NavigationConfig config : List.of(NavigationConfig.defaults(), keysOn(true, true))) {
            service(keep, config);
            int before = walkStarts.size();
            var npc = new NpcAccessSubject("porter-1", NpcAccessProfile.DEFAULT, DevWorldSnapshot.WORLD, java.util.Set.of());
            plans[k++] = service.planRoadFor(npc, new NavPoint(DevWorldSnapshot.WORLD, 1442, 42, -553),
                new NavPoint(DevWorldSnapshot.WORLD, 1421, 72, -488)).join();
            runSearches();
            assertEquals(before, walkStarts.size(), "no walk search for an NPC plan");
        }
        assertEquals(RoadPlanner.Status.FOUND, plans[0].status());
        assertEquals(plans[0].line().toString(), plans[1].line().toString(), "the same road line with the keys on");
    }

    @Test
    void theKeysAreReadAndValidated() {
        NavigationConfig.RouteEndsConfig defaults = NavigationConfig.defaults().routeEnds();
        assertFalse(defaults.reachableGoal());
        assertFalse(defaults.roadToItsEnd());
        assertEquals(12, defaults.goalCandidates());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 0, false).validate());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 33, false).validate());
    }
}
