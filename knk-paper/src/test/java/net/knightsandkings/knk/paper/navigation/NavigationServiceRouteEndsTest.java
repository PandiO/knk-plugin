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
    /** Location 53, "Garrison Watchtower Roof" (feet), on the roof floor beside the hatch (#771 item 5, F3). */
    static final double[] WATCHTOWER = {1401.5, 107, -541.5};
    /** The edge the road leaves the Keep Garrison on (its north end, 1399 80 -532): F2's goal for the tower roof live. */
    static final int GARRISON_EXIT = 2000000003;
    /** The Keep Gate (gate structure domain 5): x 1414..1430, z -523..-516. */
    static final double[] KEEP_GATE = {1414, -523, 1430, -516};

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

    // ===== F3, failure 1: the road carries the route as far as it usefully goes (smoke test 2026-11-19) =====

    private static NavigationConfig walkWeight(double weight) {
        return NavigationConfig.defaults().withRouteEnds(new NavigationConfig.RouteEndsConfig(true,
            NavigationConfig.RouteEndsConfig.DEFAULT_GOAL_CANDIDATES, false, weight));
    }

    /** The service's INFO lines during {@code body}. */
    private List<String> infoLines(Runnable body) {
        List<String> lines = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.INFO) {
                    lines.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("test");
        logger.addHandler(handler);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lines;
    }

    @Test
    void scene2WithF2sRoadPlusWalkTheRoadStopsAtTheGarrisonExitAsInTheSmokeTest() {
        service(tower, walkWeight(1));
        Route chosen = navigateTo("Keep Tower Roof", TOWER);
        assertEquals(GARRISON_EXIT, chosen.end().edgeId(), chosen.end().toString());
        assertEquals(-532, chosen.end().z(), 1);
        WalkResult walk = walkFromTheEnd(chosen, TOWER);
        assertTrue(walk.path().orElseThrow().length() > 190, "a long walk, as the live 202: " + walk);
    }

    @Test
    void scene2TheRoadGoesIntoTheKeepGateAndTheWalkIsTheShortest() {
        service(tower, NavigationConfig.defaults());
        Route today = navigateTo("Keep Tower Roof", TOWER);
        double todaysWalk = walkFromTheEnd(today, TOWER).path().orElseThrow().length();
        service.stop(player);

        service(tower, keysOn(true, false));
        List<String> log = infoLines(() -> navigateTo("Keep Tower Roof", TOWER)).stream()
            .filter(l -> l.contains("road goal")).toList(); // (the start leg's search, below the window, says no cell)
        Route chosen = route();
        double[] end = chosen.end().point();
        assertTrue(end[0] >= KEEP_GATE[0] - 1 && end[2] >= KEEP_GATE[1], "through the garrison, into the Keep Gate: "
            + chosen.end());
        WalkResult walk = walkFromTheEnd(chosen, TOWER);
        assertEquals(WalkResult.Status.FOUND, walk.status(), walk.toString());
        assertTrue(walk.path().orElseThrow().length() <= todaysWalk, "no longer than from node #74's street: " + walk);
        assertTrue(chosen.length() > today.length() - 20, "the road carries the route: " + chosen.length());

        assertEquals(1, log.size(), "one INFO line per choice: " + log);
        String line = log.get(0);
        assertTrue(line.contains("road goal for Keep Tower Roof") && line.contains("walk weight 4.0"), line);
        assertTrue(line.contains("(1408 84 -517)"), "node #74, the road onward from today's goal, is a candidate: " + line);
        assertTrue(line.contains("(1399 80 -532) road"), "the garrison exit with its road and walk numbers: " + line);
        assertTrue(line.contains("CHOSEN"), line);
    }

    @Test
    void scene3WithF2sRoadPlusWalkTheWatchtowerGoalStaysUnderTheWatchtowerAsInTheSmokeTest() {
        service(tower, NavigationConfig.defaults());
        Route today = navigateTo("Garrison Watchtower Roof", WATCHTOWER);
        assertEquals(GARRISON_EXIT, today.end().edgeId(), "under the watchtower: " + today.end());
        service.stop(player);

        service(tower, walkWeight(1));
        Route chosen = navigateTo("Garrison Watchtower Roof", WATCHTOWER);
        assertTrue(chosen.end().distanceTo(today.end()) < 1, "not moved by the key: " + chosen.end());
    }

    @Test
    void scene3TheWatchtowersWalkStartsWhereItIsShortest() {
        service(tower, NavigationConfig.defaults());
        Route today = navigateTo("Garrison Watchtower Roof", WATCHTOWER);
        double todaysWalk = walkFromTheEnd(today, WATCHTOWER).path().orElseThrow().length();
        service.stop(player);

        service(tower, keysOn(true, false));
        Route chosen = navigateTo("Garrison Watchtower Roof", WATCHTOWER);
        assertNotEquals(GARRISON_EXIT, chosen.end().edgeId(), "moved off the road under the watchtower: " + chosen.end());
        WalkResult walk = walkFromTheEnd(chosen, WATCHTOWER);
        assertEquals(WalkResult.Status.FOUND, walk.status(), walk.toString());
        assertTrue(walk.path().orElseThrow().length() < todaysWalk - 25, "a much shorter walk: " + walk + " vs " + todaysWalk);
        assertTrue(chosen.length() > today.length(), "the road carries the route further: " + chosen.length());
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

    // ===== F3, failure 3 (#771, smoke § 12 step 3a): 0 expansions from the watchtower roof =====

    @Test
    void scene3aFromEveryRoofCellTheSearchToEveryNearbyRoadPointLeavesItsStart() {
        // not reproduced on the snapshots: from every roof floor cell (the snow layers included, feet 107.125) the
        // search to the road points (every 4 blocks along each edge) within 60 blocks expands (the keep-tower window starts at y 66, the stair-house
        // snapshot adds the road below it)
        DevWorldSnapshot both = DevWorldSnapshot.merged(tower, keep);
        NavigationConfig config = NavigationConfig.defaults();
        List<double[]> starts = new ArrayList<>();
        for (int x = 1396; x <= 1406; x++) {
            for (int z = -547; z <= -536; z++) {
                String floor = both.states.get(net.knightsandkings.knk.core.util.BlockKey.pack(x, 106, z));
                String feetBlock = both.states.get(net.knightsandkings.knk.core.util.BlockKey.pack(x, 107, z));
                if (floor == null || floor.contains("ladder") || floor.endsWith("air")) {
                    continue;
                }
                if (feetBlock == null || feetBlock.endsWith("air")) {
                    starts.add(new double[] {x + 0.5, 107, z + 0.5});
                } else if (feetBlock.contains("snow")) {
                    starts.add(new double[] {x + 0.5, 107.125, z + 0.5});
                }
            }
        }
        assertTrue(starts.size() >= 15, "the roof's floor cells: " + starts.size());
        int searches = 0;
        var b = config.walk().budget(); // the shipped snaps; a few expansions show the search left its start
        var budget = new net.knightsandkings.knk.core.roads.walk.WalkBudget(20, b.maxLengthFactor(), b.maxLength(),
            b.detourAllowance(), b.climbAllowance(), b.startSnap(), b.goalSnap());
        var terrain = both.capture(new WalkBox(DevWorldSnapshot.WORLD, 1376, 36, -575, 1460, 130, -480)).terrain();
        for (var edge : both.network.edges()) {
            var line = both.network.polyline(edge.id());
            for (double along = 0; along <= line.length(); along += 4) {
                double[] t = line.pointAt(along);
                if (NavigationService.distance(1400, 106, -541, t) > 60) {
                    continue;
                }
                for (double[] from : starts) {
                    WalkResult r = new WalkSearch().find(WalkRequest.toPoint(terrain, from[0], from[1],
                        from[2], t[0], t[1], t[2], 4).withBudget(budget));
                    assertTrue(r.expansions() > 0, "from " + Arrays.toString(from) + " to " + Arrays.toString(t) + ": " + r);
                    searches++;
                }
            }
        }
        assertTrue(searches > 300, "searches: " + searches);
    }

    @Test
    void aSearchThatNeverLeavesItsStartSaysWhichEndAtInfo() {
        moveTo(new double[] {1400.5, 125, -541.5}); // in the air above the watchtower roof: no cell near the feet
        service(tower, NavigationConfig.defaults());
        List<String> log = infoLines(() -> {
            service.navigate(player, Destination.point("Keep Tower Roof", DevWorldSnapshot.WORLD, TOWER[0], TOWER[1],
                TOWER[2]));
            runSearches();
        });
        assertTrue(log.stream().anyMatch(l -> l.contains("Walk search #") && l.contains("no walkable cell near the start")
            && l.contains("start feet 1400.5 125.0 -541.5")), log.toString());
    }

    // ===== F3, failure 4 (#710, smoke § 8): the open door at 1405 104 -494 =====

    @Test
    void theOpenKeepDoorBlocksTheWayWithEitherFlagOnlyWhenItIsDeniedAsIfShut() {
        // the spiral stair's landing door (spruce, open) on the way up to the Keep Tower Roof; the search starts below it
        assertTrue(tower.states.get(net.knightsandkings.knk.core.util.BlockKey.pack(1405, 104, -494)).contains("open=true"));
        double[] from = {1402.5, 96, -496.5};
        NavigationConfig.WalkConfig walk = NavigationConfig.defaults().walk();
        WalkBox box = WalkBox.around(DevWorldSnapshot.WORLD, from[0], from[1], from[2], TOWER[0], TOWER[1] - 1, TOWER[2],
            walk.captureMargin(), -64, 320);
        var terrain = tower.capture(box).terrain();
        CellAccess deniedDoor = new net.knightsandkings.knk.core.roads.walk.DoorCellAccess(List.of(
            net.knightsandkings.knk.core.util.BlockKey.pack(1405, 104, -494),
            net.knightsandkings.knk.core.util.BlockKey.pack(1405, 105, -494)), MovementProfile.PLAYER_HEADROOM);
        for (boolean guidanceV2 : new boolean[] {false, true}) {
            WalkRequest request = WalkRequest.toPoint(terrain, from[0], from[1], from[2], TOWER[0], TOWER[1] - 1, TOWER[2], 4)
                .withBudget(walk.budget()).withProfile(walk.profile(guidanceV2));
            WalkResult open = new WalkSearch().find(request);
            assertEquals(WalkResult.Status.FOUND, open.status(), "guidance-v2 " + guidanceV2 + ": " + open);
            WalkResult denied = new WalkSearch().find(request.withAccess(deniedDoor));
            assertEquals(WalkResult.Status.NO_PATH, denied.status(), "guidance-v2 " + guidanceV2 + ": " + denied);
            assertTrue(denied.partialPath().isPresent(), "a partial trail up to the door, as live: " + denied);
        }
    }

    // ===== F3, failure 2 (#780): a route replacement does not repeat an announced maneuver =====

    /** The chat lines "In 6 m: Turn left ..." the player was sent, in order. */
    private List<String> maneuverLines() {
        List<String> lines = new ArrayList<>();
        mockingDetails(player).getInvocations().forEach(i -> {
            if (i.getMethod().getName().equals("sendMessage") && i.getArguments().length == 1
                && i.getArguments()[0] instanceof net.kyori.adventure.text.Component c) {
                String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
                if (text.matches("\\[Road\\] In .*")) {
                    lines.add(text);
                }
            }
        });
        return lines;
    }

    private void tickStill(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
    }

    /** Navigate, stand still while the goal choice and two live-tag network swaps replace the route. */
    private List<String> maneuversSaidStandingStill(NavigationConfig config) {
        moveTo(new double[] {1394.5, 46, -518.5}); // on the road, 5 blocks before the junction at node 76
        service(tower, config);
        service.navigate(player, Destination.point("Keep Tower Roof", DevWorldSnapshot.WORLD, TOWER[0], TOWER[1], TOWER[2]));
        tickStill(20);
        runSearches();
        tickStill(20);
        service.onNetworkChanged(DevWorldSnapshot.WORLD);
        tickStill(20);
        runSearches();
        tickStill(20);
        service.onNetworkChanged(DevWorldSnapshot.WORLD);
        runSearches();
        tickStill(20);
        return maneuverLines();
    }

    @Test
    void withTheKeysOnAReplacedRouteDoesNotRepeatAnAnnouncedManeuver() {
        List<String> said = maneuversSaidStandingStill(keysOn(true, true));
        assertEquals(List.of("[Road] In 5 m: Take the road on the left"), said,
            "the goal choice and two network swaps replace the route; the turn is said once");
    }

    @Test
    void withTheKeysOffANetworkSwapStillRepeatsItAsToday() {
        // flag-off identity: today every silent replacement (here the two live-tag swaps) says the turn again
        List<String> said = maneuversSaidStandingStill(NavigationConfig.defaults());
        assertEquals(3, said.size(), said.toString());
        assertEquals(1, new java.util.HashSet<>(said).size(), said.toString());
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
        assertEquals(4, defaults.goalWalkWeight(), 1e-9, "F3: road-goal-walk-weight");
        assertEquals(4, new NavigationConfig.RouteEndsConfig(true, 12, false).goalWalkWeight(), 1e-9);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 12, false, 0.5).validate());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 12, false, 101).validate());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 0, false).validate());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new NavigationConfig.RouteEndsConfig(true, 33, false).validate());
    }
}
