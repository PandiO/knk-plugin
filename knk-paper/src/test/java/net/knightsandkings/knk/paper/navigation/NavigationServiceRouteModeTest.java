package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, DESIGN § 5.4): the route mode reaches the router and the walk search on
 * both {@code /navigate} paths - the engine handle ({@code navigation.destination-engine: true}) and the raw entry
 * (false) - through the shared {@code begin}, and stays for the session's re-routes. The network: from S (0, 64, 0) to
 * T (200, 64, 0) north over a lane (Path, profile multiplier 1.5, about 209 blocks) or south over a main road (Main,
 * edge multiplier 2.0, about 215 blocks). preferred (the default) and shortest take the lane, efficient the main road.
 */
class NavigationServiceRouteModeTest {

    static final String WORLD = "world";
    static final int S = 1, T = 2, N = 3, M = 4;
    static final int E_SN = 11, E_NT = 12, E_SM = 13, E_MT = 14;

    private final RoadNetworkSnapshot snapshot = twoWays();
    private final AtomicLong tick = new AtomicLong(100);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private Location feet;
    private final List<WalkRequest> walkRequests = new ArrayList<>();
    private final List<Runnable> walkSearches = new ArrayList<>();
    private NavigationService service;

    private final Candidate keep = new Candidate("node:" + T, "Keep", "node", List.of(), T, null, 0, "catalogue",
        new Place.Node(WORLD, T, new double[] {200, 64, 0}));
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(
        List.of(Provider.fixed("catalogue", List.of(keep))), Provider.fixed("siege-match", List.of())));

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(WORLD);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Pandi");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> feet.clone());
        feet = new Location(world, 0.5, 65, 0.5);
        service = service();
    }

    static RoadNetworkSnapshot twoWays() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "lane", RoadClass.PATH, 1.5));
        b.addProfile(new RoadNetworkSnapshot.Profile(2, "main", RoadClass.MAIN, 1.0));
        b.addNode(new RoadNode(S, 0, 64, 0, RoadNodeKind.ENDPOINT, "Market", 1));
        b.addNode(new RoadNode(T, 200, 64, 0, RoadNodeKind.ENDPOINT, "Keep", 1));
        b.addNode(new RoadNode(N, 100, 64, 30, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(M, 100, 64, -40, RoadNodeKind.JUNCTION, null, 1));
        b.addEdge(edge(E_SN, S, N, 0, 0, 100, 30, 1, 1.0));
        b.addEdge(edge(E_NT, N, T, 100, 30, 200, 0, 1, 1.0));
        b.addEdge(edge(E_SM, S, M, 0, 0, 100, -40, 2, 2.0));
        b.addEdge(edge(E_MT, M, T, 100, -40, 200, 0, 2, 2.0));
        return b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int z0, int x1, int z1, int profile, double cost) {
        return new RoadEdge(id, from, to, List.of(new int[] {x0, 64, z0}, new int[] {x1, 64, z1}),
            Math.hypot(x1 - x0, z1 - z0), 3, OptionalInt.of(profile), OptionalInt.empty(), cost,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    private NavigationService service() {
        NavigationService.WalkPreparer preparer = (p, from, target, goal, destinationRegions) -> {
            Supplier<WalkRequest> request = () -> new WalkRequest(new WalkTerrain(mock(SurfaceGrid.class), GateCells.NONE,
                WalkCells.NONE), CellAccess.OPEN, MovementProfile.PLAYER, from[0], from[1], from[2], target[0], target[1],
                target[2], goal, WalkBudget.DEFAULTS);
            return CompletableFuture.completedFuture(request);
        };
        NavigationService.PolicyFactory policies = (pl, s) -> AccessPolicy.ALL_OPEN;
        TrailRenderer trail = mock(TrailRenderer.class);
        when(trail.periodTicks()).thenReturn(10);
        return new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(), w -> snapshot,
            policies, (w, id) -> Optional.empty(), new NavigationEligibility(u -> false, u -> false, u -> false),
            mock(NavigationHud.class), trail, Runnable::run, Runnable::run, tick::get, e -> { }, Logger.getLogger("test"),
            new NavigationService.Walk(preparer, request -> {
                walkRequests.add(request);
                return NavigationServiceWalkLinksTest.straight(request);
            }, walkSearches::add)));
    }

    private AllowedDestination handle() {
        NavigationContext ctx = NavigationContext.of(playerId, WORLD).withPermissions(InitialScopes.NAVIGATE_NODE);
        AllowedDestination handle = engine.resolve(ctx, "Keep", Candidate.Mode.DEFAULT).destination();
        assertNotNull(handle);
        return handle;
    }

    private Route route() {
        return service.sessionOf(playerId).orElseThrow().route().orElseThrow();
    }

    /** The way the route takes: N (the lane) or M (the main road); the player's snap may add S before it. */
    private List<Integer> nodes() {
        List<Integer> ids = route().nodeIds();
        return ids.stream().filter(id -> id == N || id == M).toList();
    }

    private void runSearches() {
        while (!walkSearches.isEmpty()) {
            List<Runnable> queued = new ArrayList<>(walkSearches);
            walkSearches.clear();
            queued.forEach(Runnable::run);
        }
    }

    @Test
    void theRawEntryRoutesInTheModeItIsGiven() {
        // navigation.destination-engine: false - the old path
        Destination keepNode = Destination.node("Keep", WORLD, T, new double[] {200, 64, 0});
        service.navigate(player, keepNode);
        assertEquals(List.of(N), nodes(), "the default: today's lane (about 360 against 388)");
        assertEquals(RouteMode.PREFERRED, route().mode());

        service.navigate(player, keepNode, RouteMode.EFFICIENT);
        assertEquals(List.of(M), nodes(), "efficient: class costs only, the main road");
        assertEquals(RouteMode.EFFICIENT, route().mode());

        service.navigate(player, keepNode, RouteMode.SHORTEST);
        assertEquals(List.of(N), nodes(), "shortest: the lane is the shorter way");
        assertEquals(RouteMode.SHORTEST, route().mode());
        assertEquals(route().length(), route().cost(), 1e-9, "length only");
    }

    @Test
    void theEngineHandleRoutesInTheModeItIsGiven() {
        // navigation.destination-engine: true - the handle
        service.navigate(player, handle());
        assertEquals(List.of(N), nodes());
        assertEquals(RouteMode.PREFERRED, route().mode());

        service.navigate(player, handle(), RouteMode.EFFICIENT);
        assertEquals(List.of(M), nodes());
        assertEquals(RouteMode.EFFICIENT, route().mode());

        service.navigate(player, handle(), RouteMode.PREFERRED);
        assertEquals(List.of(N), nodes());
    }

    @Test
    void aRerouteKeepsTheMode() {
        service.navigate(player, handle(), RouteMode.EFFICIENT);
        assertEquals(List.of(M), nodes());
        // the player walks down the main road and strays a little off it: the session re-routes in its mode
        feet = new Location(world, 50.5, 65, -26.5);
        for (int i = 0; i < 40; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
        assertEquals(RouteMode.EFFICIENT, route().mode());
        assertEquals(List.of(M), nodes());
    }

    @Test
    void aWalkLegIsSearchedInTheMode() {
        // direct mode (a target 25 blocks away): the walk search's profile
        Destination well = Destination.point("Well", WORLD, 15.5, 65, 20.5);
        service.navigate(player, well);
        runSearches();
        assertFalse(walkRequests.isEmpty());
        assertSame(MovementProfile.PLAYER, walkRequests.get(walkRequests.size() - 1).profile(),
            "the default walks with today's profile, the very same one");

        walkRequests.clear();
        service.navigate(player, well, RouteMode.SHORTEST);
        runSearches();
        MovementProfile shortest = walkRequests.get(walkRequests.size() - 1).profile();
        assertEquals(0.0, shortest.doorCost(), 0.0, "shortest: doors priced as length");
        assertEquals(1.0, shortest.waterFactor(), 0.0);
        assertEquals(0.0, shortest.dropPenalty(), 0.0);

        walkRequests.clear();
        service.navigate(player, well, RouteMode.EFFICIENT);
        runSearches();
        assertSame(MovementProfile.PLAYER, walkRequests.get(walkRequests.size() - 1).profile(),
            "efficient keeps the walk profile (no landmark weights before #709)");
    }

    @Test
    void forModeLeavesTheDefaultRequestUntouched() {
        WalkRequest request = new WalkRequest(new WalkTerrain(mock(SurfaceGrid.class), GateCells.NONE, WalkCells.NONE),
            CellAccess.OPEN, MovementProfile.PLAYER, 0.5, 65, 0.5, 10.5, 64, 0.5,
            net.knightsandkings.knk.core.roads.walk.WalkGoal.within(10.5, 64, 0.5, 1), WalkBudget.DEFAULTS);
        assertSame(request, NavigationService.forMode(request, RouteMode.PREFERRED));
        assertSame(request, NavigationService.forMode(request, RouteMode.EFFICIENT));
        assertTrue(NavigationService.forMode(request, RouteMode.SHORTEST) != request);
        assertEquals(null, NavigationService.forMode(null, RouteMode.SHORTEST));
    }

    @Test
    void anNpcPlanKeepsTodaysCosts() {
        // NPC plans name no mode: planRoadFor routes as before (preferred) - the npc-* scenarios guard the rest
        RoadNetworkSnapshot network = twoWays();
        RouteRequest request = RouteRequest.of(SnapPoint.atNode(network, S), SnapPoint.atNode(network, T),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults());
        assertEquals(RouteMode.DEFAULT, request.mode());
        assertEquals(List.of(N), new AStarRouter(network).route(request).route().nodeIds());
    }
}
