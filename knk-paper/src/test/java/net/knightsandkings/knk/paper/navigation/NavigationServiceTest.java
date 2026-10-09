package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteReason;
import net.knightsandkings.knk.core.navigation.NavigationSession;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.DomainAvailability;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.GateAvailability.GateView;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkPath;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.events.NavigationArriveEvent;
import net.knightsandkings.knk.paper.events.NavigationEndEvent;
import net.knightsandkings.knk.paper.events.NavigationRerouteEvent;
import net.knightsandkings.knk.paper.events.NavigationStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The runtime around the core session (plan Phase 4 tests): start, refusals, direct mode, re-route
 * on a gate change, the D13 re-check, ends on siege join / teleport / death, arrival. Routing runs
 * inline (both executors are {@code Runnable::run}); the gate and the region are test state.
 */
class NavigationServiceTest {

    /** A yard 20 blocks off Main Street that no road enters (its nearest road point is (20, 64, 0)). */
    private static final String MILL_YARD_REGION = "mill_yard";

    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final AtomicLong tick = new AtomicLong(100);
    private final AtomicBoolean inSiege = new AtomicBoolean();
    private final AtomicBoolean castleDenied = new AtomicBoolean();
    private final Map<Integer, AnimationState> gateStates = new ConcurrentHashMap<>(Map.of(NavigationTestNetwork.GATE_DOOR, AnimationState.OPEN));
    private final List<Event> events = new ArrayList<>();
    private final NavigationHud hud = mock(NavigationHud.class);
    private final TrailRenderer trail = mock(TrailRenderer.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private Location feet = new Location(null, 0.5, 65, 0.5);
    private NavigationService service;
    private NavigationService.PolicyFactory policies;
    private RegionShapes shapes;
    private NavigationEligibility eligibility;

    // KNG-51 walk paths: the fake capture and search
    private final List<double[]> walkTargets = new ArrayList<>();
    private final List<WalkGoal> walkGoals = new ArrayList<>();
    private final List<CompletableFuture<Supplier<WalkRequest>>> walkCaptures = new ArrayList<>();
    private final List<Runnable> walkSearches = new ArrayList<>();
    private boolean walkCaptureReady = true;
    private boolean walkCaptureHeld;
    private Function<WalkRequest, WalkResult> walkFinder = request -> found(wellDetour());

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(NavigationTestNetwork.WORLD);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Pandi");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> feet.clone());
        when(trail.periodTicks()).thenReturn(10);
        feet = new Location(world, 0.5, 65, 0.5);

        policies = (p, snapshot) -> {
            GateAvailability.GateState gates = doorId -> Optional.ofNullable(gateStates.get(doorId))
                .map(state -> new GateView(doorId, "West Gate", state, false, false, false, false, false));
            DomainAvailability.DomainLookup lookup = regionId -> NavigationTestNetwork.CASTLE_REGION.equals(regionId)
                ? Optional.of(new DomainSnapshot(NavigationTestNetwork.CASTLE_DOMAIN, "Kardenna Castle", "", regionId,
                    !castleDenied.get(), true, "Structure", Set.of(), Set.of(), Set.of(), Set.of()))
                : Optional.empty();
            return CompositeAccessPolicy.of(new StaticFlagsAvailability(), new GateAvailability(gates, doorId -> false),
                new DomainAvailability(new DomainAccessEvaluator(), lookup, Set.of(), false));
        };
        shapes = (w, id) -> switch (id) {
            case NavigationTestNetwork.CASTLE_REGION -> Optional.of(RegionShape.cuboid(190, 60, 190, 210, 80, 210));
            case MILL_YARD_REGION -> Optional.of(RegionShape.cuboid(20, 60, 20, 40, 80, 40));
            default -> Optional.empty();
        };
        eligibility = new NavigationEligibility(uuid -> false, uuid -> false, uuid -> inSiege.get());
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, policies, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
    }

    /**
     * Replaces {@link #service} (what {@link #ticks} drives) with one that has walk paths (KNG-51): the
     * capture and the search are this test's fakes; searches wait for {@link #runSearches}.
     */
    private NavigationService walkService(NavigationConfig config) {
        NavigationService.WalkPreparer preparer = (p, from, target, goal) -> {
            walkTargets.add(target.clone());
            walkGoals.add(goal);
            CompletableFuture<Supplier<WalkRequest>> capture = new CompletableFuture<>();
            walkCaptures.add(capture);
            if (!walkCaptureHeld) {
                capture.complete(walkCaptureReady ? () -> request(from, target, goal) : null);
            }
            return capture;
        };
        service = new NavigationService(new NavigationService.Deps(null, config, w -> network.snapshot, policies, shapes,
            eligibility, hud, trail, Runnable::run, Runnable::run, tick::get, events::add, Logger.getLogger("test"),
            new NavigationService.Walk(preparer, request -> walkFinder.apply(request), walkSearches::add)));
        return service;
    }

    private static WalkRequest request(double[] from, double[] target, WalkGoal goal) {
        WalkTerrain terrain = new WalkTerrain(mock(SurfaceGrid.class), GateCells.NONE, WalkCells.NONE);
        return new WalkRequest(terrain, CellAccess.OPEN, MovementProfile.PLAYER, from[0], from[1], from[2], target[0],
            target[1], target[2], goal, WalkBudget.DEFAULTS);
    }

    private static WalkResult found(List<double[]> points) {
        WalkPath path = mock(WalkPath.class);
        when(path.points()).thenReturn(points);
        return WalkResult.found(path, 42);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<double[]>> pathCaptor() {
        return ArgumentCaptor.forClass((Class<List<double[]>>) (Class<?>) List.class);
    }

    private void runSearches() {
        List<Runnable> queued = new ArrayList<>(walkSearches);
        walkSearches.clear();
        queued.forEach(Runnable::run);
    }

    private static Destination well() {
        return Destination.point("Well", NavigationTestNetwork.WORLD, 20.5, 65, 0.5);
    }

    /** Around a wall between the player at (0.5, 65, 0.5) and the well: 8 north, 20 east, 8 south (36 blocks). */
    private static List<double[]> wellDetour() {
        return List.of(new double[] {0.5, 64, 0.5}, new double[] {0.5, 64, 8.5}, new double[] {20.5, 64, 8.5},
            new double[] {20.5, 64, 0.5});
    }

    private NavigationService.DirectLeg.Status legStatus(NavigationService s) {
        return s.legOf(playerId).orElseThrow().status;
    }

    private Destination cinixKeep() {
        return Destination.point("Cinix Keep", NavigationTestNetwork.WORLD, 200.5, 65, 0.5);
    }

    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    private void moveTo(double x, double y, double z) {
        feet = new Location(world, x, y, z);
    }

    /** The route avoids the gate edge: A → B → D → C' → C (about 400 blocks from the player's spot). */
    private static void assertDetour(NavigationSession session) {
        var route = session.route().orElseThrow();
        assertTrue(route.steps().stream().noneMatch(s -> s.edge().id() == NavigationTestNetwork.E_BC), "no gate edge in " + route);
        assertTrue(route.length() > 390 && route.length() <= 400, "detour length " + route.length());
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
    }

    @Test
    void startsARoutedSessionAlongTheRoad() {
        service.navigate(player, cinixKeep());

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertEquals(NavigationSession.State.GUIDING, session.state());
        assertEquals(200, session.route().orElseThrow().length(), 1e-6, "A → B → C along Main Street");
        assertTrue(messages().get(0).contains("Navigating to Cinix Keep"));
        assertTrue(events.get(0) instanceof NavigationStartEvent);
        verify(hud).update(any(), anyString(), anyDouble(), anyDouble());
        verify(trail).drawRoute(any(), any(), anyDouble(), any());
    }

    @Test
    void refusesWhenThePlayerIsTooFarFromARoad() {
        moveTo(0.5, 65, 300.5);

        service.navigate(player, cinixKeep());

        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().get(0).contains("too far from a road"));
    }

    @Test
    void refusesWhenTheDestinationIsTooFarFromARoad() {
        service.navigate(player, Destination.point("Far Mill", NavigationTestNetwork.WORLD, 900.5, 65, 900.5));

        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().get(0).contains("Far Mill is too far from any road"));
    }

    @Test
    void aDestinationHighAboveARoadIsReachedByRoadThenItsLastLeg() {
        // live test 2026-10-09 (N15): a tower roof 30 blocks above the road measured 120 with the height ×4
        service.navigate(player, Destination.point("Tower Roof", NavigationTestNetwork.WORLD, 60.5, 95, 0.5));

        assertTrue(service.isNavigating(playerId), messages().toString());
        assertFalse(service.isDirect(playerId), "67 blocks away: by road first");
        assertTrue(messages().stream().noneMatch(m -> m.contains("too far from any road")), messages().toString());
        assertEquals(60, service.sessionOf(playerId).orElseThrow().route().orElseThrow().length(), 1.0,
            "along Main Street to below the roof");
    }

    @Test
    void aPlayerUnderABridgeStillStartsOnTheRoadBelow() {
        // the start keeps the height ×4: the bridge 8 blocks overhead is nearer in plain 3D than the road 10 blocks aside
        RoadNetworkSnapshot bridge = RoadNetworkSnapshot.builder(NavigationTestNetwork.WORLD)
            .addProfile(network.snapshot.profiles().get(1))
            .addNode(node(80, -60, 64, 0, 1)).addNode(node(81, 60, 64, 0, 1))
            .addNode(node(82, 0, 72, -60, 1)).addNode(node(83, 0, 72, 60, 1))
            .addEdge(edge(80, 80, 81, new int[] {-60, 64, 0}, new int[] {60, 64, 0}))
            .addEdge(edge(81, 83, 82, new int[] {0, 72, 60}, new int[] {0, 72, -60}))
            .addEdge(edge(82, 81, 83, new int[] {60, 64, 0}, new int[] {0, 72, 60}))
            .build();
        serviceOn(bridge);
        moveTo(0.5, 65, 10.5);

        service.navigate(player, Destination.point("South Bank", NavigationTestNetwork.WORLD, 0.5, 73, -59.5));

        assertTrue(service.isNavigating(playerId), messages().toString());
        assertEquals(80, service.sessionOf(playerId).orElseThrow().route().orElseThrow().steps().get(0).edge().id(),
            "starts on the road below, not on the bridge");
    }

    @Test
    void aHighDestinationReSnapsToTheStartsNetworkWithoutTheHeightWeight() {
        // N12 with N15: the roof's nearest road is a stretch that joins nothing; Main Street is 36 blocks off (plain)
        RoadNetworkSnapshot withStub = RoadNetworkSnapshot.builder(NavigationTestNetwork.WORLD)
            .addNodes(network.snapshot.nodes()).addEdges(network.snapshot.edges())
            .addProfile(network.snapshot.profiles().get(1))
            .addNode(node(90, 55, 90, 20, 99)).addNode(node(91, 65, 90, 20, 99))
            .addEdge(edge(90, 90, 91, new int[] {55, 90, 20}, new int[] {65, 90, 20}))
            .build();
        serviceOn(withStub);

        service.navigate(player, Destination.point("Tower Roof", NavigationTestNetwork.WORLD, 60.5, 95, 20.5));

        assertTrue(service.isNavigating(playerId), messages().toString());
        assertTrue(messages().stream().noneMatch(m -> m.contains("No road connects")), messages().toString());
        assertEquals(NavigationTestNetwork.E_AB, service.sessionOf(playerId).orElseThrow().route().orElseThrow()
            .steps().get(0).edge().id());
    }

    private void serviceOn(RoadNetworkSnapshot snapshot) {
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> snapshot, policies, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
    }

    private static net.knightsandkings.knk.core.domain.roads.RoadNode node(int id, int x, int y, int z, int component) {
        return new net.knightsandkings.knk.core.domain.roads.RoadNode(id, x, y, z,
            net.knightsandkings.knk.core.domain.roads.RoadNodeKind.ENDPOINT, null, component);
    }

    private static RoadEdge edge(int id, int from, int to, int[] a, int[] b) {
        double length = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
        return new RoadEdge(id, from, to, List.of(a, b), length, 3, java.util.OptionalInt.of(1),
            java.util.OptionalInt.empty(), 1.0, java.util.EnumSet.noneOf(net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag.class),
            List.of(), List.of(), List.of(), net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.DETECTED, false);
    }

    @Test
    void aNearbyTargetUsesDirectMode() {
        service.navigate(player, Destination.point("Well", NavigationTestNetwork.WORLD, 20.5, 65, 0.5));

        assertTrue(service.isDirect(playerId));
        assertTrue(messages().get(0).contains("Well is"));
        verify(trail).drawDirect(any(), any());

        moveTo(19.5, 65, 0.5);
        ticks(1);

        assertFalse(service.isNavigating(playerId));
        assertTrue(events.stream().anyMatch(e -> e instanceof NavigationArriveEvent));
    }

    @Test
    void aStartEventListenerCanCancel() {
        NavigationService cancelling = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, (p, s) -> net.knightsandkings.knk.core.roads.route.AccessPolicy.ALL_OPEN,
            (w, id) -> Optional.empty(), new NavigationEligibility(u -> false, u -> false, u -> false), hud, trail,
            Runnable::run, Runnable::run, tick::get, event -> ((NavigationStartEvent) event).setCancelled(true), null));

        cancelling.navigate(player, cinixKeep());

        assertFalse(cancelling.isNavigating(playerId));
    }

    @Test
    void aClosedGateOnTheRouteTriggersARerouteWithTheReason() {
        service.navigate(player, cinixKeep());
        events.clear();

        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertDetour(session);
        assertTrue(messages().stream().anyMatch(m -> m.contains("West Gate is closed") && m.contains("recalculating")));
        NavigationRerouteEvent reroute = (NavigationRerouteEvent) events.stream()
            .filter(e -> e instanceof NavigationRerouteEvent).findFirst().orElseThrow();
        assertEquals(RouteReason.ELEMENT_BLOCKED, reroute.getReason());
    }

    @Test
    void aShortStretchThatJoinsNothingIsPassedOverForTheConnectedRoad() {
        // live test 2026-10-08 (N12): 15 blocks from the network, "No road connects you to X" - the nearest road
        // was a stretch the build left on its own
        RoadNetworkSnapshot withStub = RoadNetworkSnapshot.builder(NavigationTestNetwork.WORLD)
            .addNodes(network.snapshot.nodes()).addEdges(network.snapshot.edges())
            .addProfile(network.snapshot.profiles().get(1))
            .addNode(new net.knightsandkings.knk.core.domain.roads.RoadNode(90, 40, 64, 20,
                net.knightsandkings.knk.core.domain.roads.RoadNodeKind.ENDPOINT, null, 99))
            .addNode(new net.knightsandkings.knk.core.domain.roads.RoadNode(91, 60, 64, 20,
                net.knightsandkings.knk.core.domain.roads.RoadNodeKind.ENDPOINT, null, 99))
            .addEdge(new net.knightsandkings.knk.core.domain.roads.RoadEdge(90, 90, 91,
                List.of(new int[] {40, 64, 20}, new int[] {60, 64, 20}), 20, 2, java.util.OptionalInt.empty(),
                java.util.OptionalInt.empty(), 1.0, java.util.EnumSet.noneOf(net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag.class),
                List.of(), List.of(), List.of(), net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.RECORDED, false))
            .build();
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> withStub, policies, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
        moveTo(50.5, 65, 14.5); // 5.5 blocks from the stub, 14.5 from Main Street

        service.navigate(player, cinixKeep());

        assertTrue(service.isNavigating(playerId), messages().toString());
        assertTrue(messages().stream().noneMatch(m -> m.contains("No road connects")), messages().toString());
        assertEquals(NavigationTestNetwork.E_AB, service.sessionOf(playerId).orElseThrow().route().orElseThrow()
            .steps().get(0).edge().id());
    }

    @Test
    void aGateClosingBehindThePlayerDoesNotBlockTheRoute() {
        // live test 2026-10-08 (C3, N10): through the gate, then it closes (or a /knk gate open animates it)
        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));
        for (double x = 10.5; x <= 200.5; x += 5) {
            moveTo(x, 65, 0.5);
            ticks(1);
        }
        for (double z = 5.5; z <= 30.5; z += 5) {
            moveTo(200.5, 65, z);
            ticks(1);
        }
        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertTrue(session.along() > 200, "past the gate edge: " + session.along());

        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.OPENING);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        ticks(NavigationService.RECHECK_TICKS + 1);

        assertTrue(messages().stream().noneMatch(m -> m.contains("West Gate")), messages().toString());
        assertEquals(NavigationSession.State.GUIDING, session.state());
    }

    /**
     * The production part check, with a door on Main Street's gate edge at x = 150: the stretch is tagged by
     * {@link NavigationAccess#partOf} and judged by the request's own (caching) policy, like NavigationAccess.
     */
    private NavigationService.PolicyFactory gateDoorAtX150() {
        net.knightsandkings.knk.core.roads.build.GateCells door = (x, y, z) -> x == 150 && z == 0 && y >= 64 && y <= 66
            ? java.util.OptionalInt.of(NavigationTestNetwork.GATE_DOOR) : java.util.OptionalInt.empty();
        return new NavigationService.PolicyFactory() {
            @Override
            public AccessPolicy policyFor(Player p, RoadNetworkSnapshot snapshot) {
                return policies.policyFor(p, snapshot);
            }

            @Override
            public boolean partOpen(Player p, RoadNetworkSnapshot snapshot, RoadEdge edge, double fromAlong, double toAlong,
                                    AccessPolicy policy) {
                return NavigationAccess.partOf(edge, snapshot.polyline(edge).subPolyline(fromAlong, toAlong), b -> Set.of(), door)
                    .map(part -> policy.checkPart(part).isUsable()).orElse(true);
            }

            @Override
            public List<RouteRequest.GoalSides> goalSides(Player p, RoadNetworkSnapshot snapshot, List<SnapPoint> goals,
                                                          AccessPolicy policy) {
                List<RouteRequest.GoalSides> sides = new ArrayList<>();
                for (SnapPoint g : goals) {
                    RoadEdge edge = snapshot.requireEdge(g.edgeId());
                    double length = snapshot.polyline(edge).length();
                    sides.add(policy.check(edge).isBlocked()
                        ? new RouteRequest.GoalSides(partOpen(p, snapshot, edge, 0, g.along(), policy),
                            partOpen(p, snapshot, edge, length, g.along(), policy))
                        : null);
                }
                return sides;
            }
        };
    }

    private void serviceWith(NavigationService.PolicyFactory factory) {
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, factory, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
    }

    @Test
    void aDestinationOnTheOpenSideOfAClosedGateIsReached() {
        // live test 2026-10-09 (A8/A9 with the gate closed, N14): South Gate's spawn snaps onto the gate's road on the
        // town side of the door; the whole edge counted as blocked, and nothing was found
        serviceWith(gateDoorAtX150());
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);

        service.navigate(player, Destination.point("South Gate", NavigationTestNetwork.WORLD, 135.5, 65, 0.5));

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertTrue(session.explanation().isEmpty(), "a full route: " + messages());
        Route route = session.route().orElseThrow();
        assertEquals(NavigationTestNetwork.E_BC, route.steps().get(route.steps().size() - 1).edge().id());
        assertEquals(135, route.end().x(), 1.0);
        ticks(NavigationService.RECHECK_TICKS + 1);
        assertTrue(messages().stream().noneMatch(m -> m.contains("West Gate") || m.contains("No route")), messages().toString());
    }

    @Test
    void aRouteStartingPastTheGateOnItsEdgeIsNotBlockedByIt() {
        // live test 2026-10-08 run 5 (C3): navigation started on the town side of the South Gate, on the gate's own
        // edge; the step walks only from the player to the node, but the whole edge's verdict was used
        serviceWith(gateDoorAtX150());
        moveTo(170.5, 65, 0.5);
        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));
        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertEquals(NavigationTestNetwork.E_BC, session.route().orElseThrow().steps().get(0).edge().id());

        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.OPENING);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        ticks(NavigationService.RECHECK_TICKS + 1);

        assertTrue(messages().stream().noneMatch(m -> m.contains("West Gate")), messages().toString());
        assertEquals(NavigationSession.State.GUIDING, session.state());
    }

    @Test
    void throughTheOpenGateThenItClosesBehindThePlayer() {
        // live test 2026-10-09 (C3, the developer's procedure): /nav in front of the gate, open it, walk through,
        // stop a little past it, close it - the part ahead was answered from the policy's cached whole-edge verdict
        serviceWith(gateDoorAtX150());
        moveTo(120.5, 65, 0.5);
        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));
        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        for (double x = 125.5; x <= 160.5; x += 5) {
            moveTo(x, 65, 0.5);
            ticks(1);
        }

        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSING);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        ticks(NavigationService.RECHECK_TICKS + 1);

        assertTrue(messages().stream().noneMatch(m -> m.contains("West Gate")), messages().toString());
        assertEquals(NavigationSession.State.GUIDING, session.state());

        // still in front of it, the closing gate does block
        clearInvocations(player);
        serviceWith(gateDoorAtX150());
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.OPEN);
        moveTo(120.5, 65, 0.5);
        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        service.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        assertTrue(messages().stream().anyMatch(m -> m.contains("West Gate")), messages().toString());
    }

    @Test
    void theSafetyNetRecheckCatchesAGateClosedWithoutAnEvent() {
        service.navigate(player, cinixKeep());
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);

        ticks(NavigationService.RECHECK_TICKS + 1);

        assertDetour(service.sessionOf(playerId).orElseThrow());
    }

    @Test
    void standingOnTheRoadOfAClosedGateTheOpenSideLeadsToTheDetour() {
        // live test 2026-10-08 (N6): in front of the closed gate, on its road - the way back is open
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);
        List<RouteRequest.StartSides> asked = new ArrayList<>();
        NavigationService.PolicyFactory withSides = new NavigationService.PolicyFactory() {
            @Override
            public AccessPolicy policyFor(Player p, RoadNetworkSnapshot snapshot) {
                return policies.policyFor(p, snapshot);
            }

            @Override
            public RouteRequest.StartSides startSides(Player p, RoadNetworkSnapshot snapshot, SnapPoint start, AccessPolicy policy) {
                RouteRequest.StartSides sides = new RouteRequest.StartSides(true, false);
                asked.add(sides);
                return sides;
            }
        };
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, withSides, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
        moveTo(140.5, 65, 0.5);

        service.navigate(player, cinixKeep());

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertTrue(session.explanation().isEmpty(), "a full route, not a partial one");
        assertEquals(NavigationTestNetwork.E_BC, session.route().orElseThrow().steps().get(0).edge().id());
        assertFalse(session.route().orElseThrow().steps().get(0).forward(), "back towards B");
        assertEquals(1, asked.size());
        ticks(NavigationService.RECHECK_TICKS + 1);
        assertTrue(messages().stream().noneMatch(m -> m.contains("No open route") || m.contains("arrived")
            || m.contains("recalculating")), messages().toString());
        assertTrue(service.isNavigating(playerId));
    }

    @Test
    void aDomainAskedForWithoutSpawnIsReachedByBeingInItsRegion() {
        // live test 2026-10-08 (B2, N9): inside Merchant's District, "/nav Merchant's District" led to its spawn
        moveTo(195.5, 65, 195.5);

        service.navigate(player, Destination.domainPoint("Kardenna Castle", NavigationTestNetwork.WORLD, 205.5, 65, 205.5,
            NavigationTestNetwork.CASTLE_REGION));
        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You are already in Kardenna Castle")), messages().toString());

        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 205.5, 65, 205.5));
        assertTrue(service.isNavigating(playerId), "with \"spawn\" the point is the destination");
    }

    @Test
    void aDeniedDestinationDomainGivesAPartialRouteToItsEdge() {
        castleDenied.set(true);

        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertTrue(session.explanation().isPresent());
        assertTrue(session.explanation().get().isDomainBlock());
        assertTrue(messages().stream().anyMatch(m -> m.contains("You may not enter Kardenna Castle") && m.contains("Guiding you to its edge")));
        verify(trail).drawRoute(any(), any(), anyDouble(), isNull());

        // live test 2026-10-08, N5: the edge of the domain is no arrival
        double[] end = session.route().orElseThrow().end().point();
        moveTo(end[0], end[1] + 1, end[2]);
        ticks(1);
        assertTrue(service.isNavigating(playerId), "still guiding, waiting for the way to open");
        assertTrue(events.stream().noneMatch(e -> e instanceof NavigationArriveEvent));
        assertTrue(messages().stream().noneMatch(m -> m.contains("You have arrived")), messages().toString());
        assertTrue(messages().stream().anyMatch(m -> m.contains("End of the open route to Kardenna Castle")), messages().toString());
        ticks(5);
        assertEquals(1, messages().stream().filter(m -> m.contains("End of the open route")).count(), "said once");
    }

    @Test
    void joiningASiegeEndsTheSession() {
        service.navigate(player, cinixKeep());
        inSiege.set(true);

        ticks(NavigationService.RECHECK_TICKS + 1);

        assertFalse(service.isNavigating(playerId));
        NavigationEndEvent end = (NavigationEndEvent) events.stream().filter(e -> e instanceof NavigationEndEvent).findFirst().orElseThrow();
        assertEquals(EndReason.SIEGE, end.getReason());
        verify(hud).hide(player);
    }

    @Test
    void aLongTeleportEndsTheSessionButAShortOneDoesNot() {
        service.navigate(player, cinixKeep());

        service.onTeleport(player, new Location(world, 0, 65, 0), new Location(world, 10, 65, 0));
        assertTrue(service.isNavigating(playerId));

        service.onTeleport(player, new Location(world, 0, 65, 0), new Location(world, 40, 65, 0));
        assertFalse(service.isNavigating(playerId));
        assertEquals(EndReason.TELEPORT, ((NavigationEndEvent) events.get(events.size() - 1)).getReason());
    }

    @Test
    void deathAndStopEndTheSession() {
        service.navigate(player, cinixKeep());
        service.end(player, EndReason.DEATH);
        assertFalse(service.isNavigating(playerId));

        service.navigate(player, cinixKeep());
        assertTrue(service.stop(player));
        assertFalse(service.stop(player), "nothing left to stop");
        assertTrue(messages().stream().anyMatch(m -> m.contains("Navigation stopped")));
    }

    @Test
    void arrivingAtTheRouteEndFiresTheArriveEvent() {
        service.navigate(player, cinixKeep());

        moveTo(199.5, 65, 0.5);
        ticks(1);

        assertFalse(service.isNavigating(playerId));
        assertTrue(events.stream().anyMatch(e -> e instanceof NavigationArriveEvent));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You have arrived at Cinix Keep")));
    }

    @Test
    void aRegionDestinationTheEPlayerIsInsideIsRefusedAsAlreadyThere() {
        moveTo(200.5, 65, 200.5);

        service.navigate(player, Destination.region("Kardenna Castle", NavigationTestNetwork.WORLD, NavigationTestNetwork.CASTLE_REGION));

        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().get(0).contains("already in Kardenna Castle"));
    }

    @Test
    void aRegionDestinationRoutesToTheRoadsEntryIntoIt() {
        service.navigate(player, Destination.region("Kardenna Castle", NavigationTestNetwork.WORLD, NavigationTestNetwork.CASTLE_REGION));

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        double[] end = session.route().orElseThrow().end().point();
        assertEquals(200, end[0], 1e-6);
        assertTrue(end[2] >= 189 && end[2] <= 191, "the route ends where the castle edge crosses the region border, got z=" + end[2]);
    }

    private Destination castleRegion() {
        return Destination.region("Kardenna Castle", NavigationTestNetwork.WORLD, NavigationTestNetwork.CASTLE_REGION);
    }

    @Test
    void aRegionWithinDirectRangeStillFollowsTheRoadIntoIt() {
        // 40 blocks from the castle region's edge (direct range), 15 from the castle road
        moveTo(215.5, 65, 150.5);

        service.navigate(player, castleRegion());

        assertFalse(service.isDirect(playerId), "fix plan 5.5 item 2: the road, not a straight line through terrain");
        double[] end = service.sessionOf(playerId).orElseThrow().route().orElseThrow().end().point();
        assertEquals(200, end[0], 1e-6);
        assertTrue(end[2] >= 189 && end[2] <= 191, "ends where the road enters the region, got z=" + end[2]);
    }

    @Test
    void aRegionNearerThanAnyRoadIsWalkedToStraight() {
        moveTo(180.5, 65, 195.5); // 10 blocks west of the castle region, 20 from the castle road

        service.navigate(player, castleRegion());

        assertTrue(service.isDirect(playerId));
        verify(trail).drawDirect(any(), any());

        moveTo(190.5, 65, 195.5);
        ticks(1);

        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You have arrived at Kardenna Castle")));
    }

    @Test
    void aRegionNoRoadEntersIsReachedByRoadThenAShortLastLeg() {
        Destination yard = Destination.region("Mill Yard", NavigationTestNetwork.WORLD, MILL_YARD_REGION);

        service.navigate(player, yard); // 28 blocks from the yard: direct range, but the road gets closer

        assertFalse(service.isDirect(playerId));
        double[] end = service.sessionOf(playerId).orElseThrow().route().orElseThrow().end().point();
        assertEquals(20, end[0], 1e-6, "the road's nearest approach to the yard");
        assertEquals(0, end[2], 1e-6);

        moveTo(20.5, 65, 0.5);
        ticks(1);
        assertTrue(service.isDirect(playerId), "the last off-road leg");

        moveTo(25.5, 65, 25.5);
        ticks(1);
        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You have arrived at Mill Yard")));
    }

    @Test
    void theLastLegAfterTheRoadsEndUsesTheDirectReCheck() {
        Destination yard = Destination.region("Mill Yard", NavigationTestNetwork.WORLD, MILL_YARD_REGION);
        service.navigate(player, yard);
        moveTo(20.5, 65, 0.5);
        ticks(1);
        assertTrue(service.isDirect(playerId), "the last off-road leg");

        moveTo(20.5, 65, -20.5); // back past the road's end, away from the yard
        ticks(NavigationService.RECHECK_TICKS);

        assertTrue(service.isDirect(playerId));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You're heading away from Mill Yard - recalculating.")));
        ArgumentCaptor<double[]> target = ArgumentCaptor.forClass(double[].class);
        verify(trail, atLeastOnce()).drawDirect(any(), target.capture());
        double[] last = target.getValue();
        assertEquals(20.5, last[0], 1e-6, "the yard's closest point to where the player is now");
        assertEquals(20, last[2], 1e-6);
    }

    @Test
    void aPlayerInTheRegionsLastBlockColumnIsAlreadyThere() {
        moveTo(210.7, 65, 200.5); // block 210 is inside the cuboid 190..210, though 210.7 > 210

        service.navigate(player, castleRegion());

        assertFalse(service.isNavigating(playerId));
        assertTrue(messages().get(0).contains("already in Kardenna Castle"));
    }

    @Test
    void theAlreadyThereCheckUsesTheServersRegionContainment() {
        RegionShapes worldGuardSaysInside = new RegionShapes() {
            @Override
            public Optional<RegionShape> shape(String w, String id) {
                return Optional.of(RegionShape.cuboid(190, 60, 190, 210, 80, 210));
            }

            @Override
            public boolean containsFeet(String w, String id, RegionShape shape, double x, double y, double z) {
                return true; // e.g. WorldGuard's polygon test on a region the shape copy disagrees with
            }
        };
        NavigationService withWorldGuard = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, (p, s) -> net.knightsandkings.knk.core.roads.route.AccessPolicy.ALL_OPEN,
            worldGuardSaysInside, new NavigationEligibility(u -> false, u -> false, u -> false), hud, trail,
            Runnable::run, Runnable::run, tick::get, events::add, null));

        withWorldGuard.navigate(player, castleRegion());

        assertFalse(withWorldGuard.isNavigating(playerId));
        assertTrue(messages().get(0).contains("already in Kardenna Castle"));
    }

    @Test
    void directModeRecalculatesWhenThePlayerWalksAway() {
        service.navigate(player, Destination.point("Well", NavigationTestNetwork.WORLD, 20.5, 65, 0.5));
        assertTrue(service.isDirect(playerId));
        ticks(NavigationService.RECHECK_TICKS);
        assertTrue(messages().stream().noneMatch(m -> m.contains("heading away")), "standing still is no drift");

        moveTo(-20.5, 65, 0.5); // 41 blocks away, 21 farther than the start
        ticks(NavigationService.RECHECK_TICKS);

        assertTrue(service.isNavigating(playerId));
        assertTrue(service.isDirect(playerId));
        assertTrue(messages().stream().anyMatch(m -> m.contains("You're heading away from Well - recalculating.")));
        NavigationRerouteEvent reroute = (NavigationRerouteEvent) events.stream()
            .filter(e -> e instanceof NavigationRerouteEvent).findFirst().orElseThrow();
        assertEquals(RouteReason.OFF_ROUTE, reroute.getReason());
        verify(trail, atLeastOnce()).drawDirect(any(), any());

        long recalculations = messages().stream().filter(m -> m.contains("heading away")).count();
        ticks(NavigationService.RECHECK_TICKS);
        assertEquals(recalculations, messages().stream().filter(m -> m.contains("heading away")).count(),
            "no new drift while the player stays where the target was re-derived from");

        moveTo(19.5, 65, 0.5);
        ticks(1);
        assertFalse(service.isNavigating(playerId), "still arrives normally");
    }

    @Test
    void directModeToARegionReAimsAtItsClosestPointAfterDrifting() {
        moveTo(180.5, 65, 195.5);
        service.navigate(player, castleRegion());
        assertTrue(service.isDirect(playerId));

        moveTo(170.5, 65, 230.5); // walked off to the north-west, 20+ blocks from the castle's corner
        ticks(NavigationService.RECHECK_TICKS);

        assertTrue(messages().stream().anyMatch(m -> m.contains("heading away from Kardenna Castle")));
        ArgumentCaptor<double[]> target = ArgumentCaptor.forClass(double[].class);
        verify(trail, atLeastOnce()).drawDirect(any(), target.capture());
        double[] last = target.getValue();
        assertEquals(190, last[0], 1e-6, "the region's closest point to where the player is now");
        assertEquals(210, last[2], 1e-6);
    }

    @Test
    void aRoutedSessionStillSaysYouLeftTheRoad() {
        service.navigate(player, cinixKeep());

        moveTo(50.5, 65, 20.5); // 20 blocks off Main Street
        ticks(net.knightsandkings.knk.core.navigation.SessionParameters.DEFAULT_REROUTE_AFTER_TICKS + 5);

        assertTrue(messages().stream().anyMatch(m -> m.contains("You left the road - recalculating.")));
        assertTrue(service.isNavigating(playerId));
    }

    @Test
    void aStreetDestinationEndsOnTheStreet() {
        moveTo(100.5, 65, 100.5); // at D, off Main Street

        service.navigate(player, Destination.street("Main Street", NavigationTestNetwork.WORLD, NavigationTestNetwork.STREET_MAIN));

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertEquals(100, session.route().orElseThrow().length(), 1, "D → B, the nearest point of the street");
    }

    @Test
    void aNetworkSwapWithoutTheDestinationEndsTheSession() {
        service.navigate(player, Destination.node("Cinix Keep", NavigationTestNetwork.WORLD, NavigationTestNetwork.C, new double[] {200, 64, 0}));
        NavigationService other = service;

        other.onNetworkChanged(NavigationTestNetwork.WORLD);
        assertTrue(other.isNavigating(playerId), "the same network keeps the session");
    }

    @Test
    void aSwapToTheRoutingViewMovesTheRouteOntoTheView() {
        // live test 2026-10-09: the first live-tag pass after a reload swapped the stored network for the routing
        // view, whose edge ids differ; the session kept its old route and its old ManeuverBuilder
        // ("unknown road edge 5385")
        AtomicReference<net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot> current =
            new AtomicReference<>(network.snapshot);
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> current.get(), policies, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
        service.navigate(player, cinixKeep());
        var view = net.knightsandkings.knk.core.roads.route.RoutingView.build(network.snapshot,
            Map.of(NavigationTestNetwork.E_BC, List.of(
                new net.knightsandkings.knk.core.roads.route.RoutingView.Span(0, 48.5, List.of(), List.of()),
                new net.knightsandkings.knk.core.roads.route.RoutingView.Span(48.5, 50.5, List.of(),
                    List.of(NavigationTestNetwork.GATE_DOOR)),
                new net.knightsandkings.knk.core.roads.route.RoutingView.Span(50.5, 100, List.of(), List.of()))));
        int before = messages().size();

        current.set(view);
        service.onNetworkChanged(NavigationTestNetwork.WORLD);

        assertTrue(service.isNavigating(playerId));
        var route = service.sessionOf(playerId).orElseThrow().route().orElseThrow();
        route.steps().forEach(step -> assertTrue(view.edge(step.edge().id()).isPresent(), "every step is a view edge"));
        assertTrue(route.steps().stream().anyMatch(step -> view.piece(step.edge().id()).isPresent()), "through the pieces");
        assertEquals(before, messages().size(), "the swap is silent");

        moveTo(120.5, 65, 0.5);
        ticks(100); // guidance and re-checks read the route against the view
        assertTrue(service.isNavigating(playerId));
    }

    // ==================== KNG-51 walk paths (LAST_MILE_PATHFINDING.md §7, §12) ====================

    @Test
    void aDirectLegDrawsTheStraightLineAtOnceThenAdoptsTheWalkPath() {
        NavigationService walking = walkService(NavigationConfig.defaults());

        walking.navigate(player, well());

        verify(trail).drawDirect(any(), any());
        assertEquals(NavigationService.DirectLeg.Status.PENDING, legStatus(walking), "the player always has a trail");
        assertEquals(1, walkTargets.size());
        assertArrayEquals(new double[] {20.5, 64, 0.5}, walkTargets.get(0), 1e-9, "the target as a floor point");
        assertTrue(walkGoals.get(0).reached(17.5, 64, 0.5), "arrive-distance 4");
        assertFalse(walkGoals.get(0).reached(10.5, 64, 0.5));

        runSearches();

        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(walking));
        ArgumentCaptor<List<double[]>> drawn = pathCaptor();
        verify(trail).drawPath(any(), drawn.capture());
        assertEquals(wellDetour().size(), drawn.getValue().size());
        assertArrayEquals(wellDetour().get(2), drawn.getValue().get(2), 1e-9, "the walk path's floor points");
        ticks(10);
        verify(trail, times(2)).drawPath(any(), any());
        verify(trail, times(1)).drawDirect(any(), any());
        assertTrue(messages().get(0).contains("Well is"), "the start message is unchanged");

        moveTo(19.5, 65, 0.5);
        ticks(1);
        assertFalse(walking.isNavigating(playerId), "arrival stays Euclidean");
        assertTrue(events.stream().anyMatch(e -> e instanceof NavigationArriveEvent));
    }

    @Test
    void noPathOrABudgetOutSaysSoAndDrawsNoStraightLine() {
        // live test 2026-10-08 (A8/A9, N8): no straight line through what blocks the way
        List<Runnable> setups = List.of(
            () -> walkFinder = r -> WalkResult.noPath("unreachable", 9000),
            () -> walkFinder = r -> WalkResult.fallback("expansion budget", 20000));
        for (Runnable setup : setups) {
            setup.run();
            clearInvocations(trail, player);
            NavigationService walking = walkService(NavigationConfig.defaults());
            moveTo(40.5, 65, 60.5); // 60 blocks from any road: the roads cannot help either (N13)
            walking.navigate(player, Destination.point("Well", NavigationTestNetwork.WORLD, 60.5, 65, 60.5));
            runSearches();

            assertEquals(NavigationService.DirectLeg.Status.NO_PATH, legStatus(walking));
            ticks(10);
            verify(trail, never()).drawPath(any(), any());
            verify(trail, times(1)).drawDirect(any(), any()); // only while the search ran
            assertEquals(1, messages().stream().filter(m -> m.contains("No conventional path to Well found")).count());
            ticks(NavigationService.RECHECK_TICKS * 4);
            runSearches();
            assertEquals(1, messages().stream().filter(m -> m.contains("No conventional path")).count(), "said once per leg");
            assertTrue(walking.isNavigating(playerId), "the leg stays; a door may open");
            walking.stop(player);
        }
    }

    @Test
    void aNearbyTargetTheWalkSearchCannotReachIsReachedByTheRoads() {
        // live test 2026-10-08 run 5 (A8/A9, N13): walking back and round by road reaches it
        walkFinder = r -> WalkResult.noPath("target unreachable", 900);
        NavigationService walking = walkService(NavigationConfig.defaults());

        walking.navigate(player, well());
        runSearches();

        assertTrue(walking.isNavigating(playerId));
        assertFalse(walking.isDirect(playerId), "a routed navigation now");
        assertTrue(messages().stream().anyMatch(m -> m.contains("following the roads instead")), messages().toString());
        assertTrue(messages().stream().noneMatch(m -> m.contains("No conventional path")), messages().toString());
        assertTrue(walking.sessionOf(playerId).orElseThrow().route().isPresent());
    }

    @Test
    void aNearbyHighTargetTheWalkSearchCannotReachIsTriedByRoadWithoutTheHeightWeight() {
        // N13 with N15: 30 blocks above the road, so with the height ×4 no road was near enough to try
        walkFinder = r -> WalkResult.noPath("target unreachable", 900);
        NavigationService walking = walkService(NavigationConfig.defaults());

        walking.navigate(player, Destination.point("Tower Roof", NavigationTestNetwork.WORLD, 30.5, 95, 0.5));
        runSearches();

        assertTrue(walking.isNavigating(playerId));
        assertFalse(walking.isDirect(playerId), "a routed navigation now");
        assertTrue(messages().stream().anyMatch(m -> m.contains("following the roads instead")), messages().toString());
    }

    @Test
    void aFailedSearchAndAnUncapturedLegKeepTheStraightLine() {
        List<Runnable> setups = List.of(
            () -> walkFinder = r -> {
                throw new IllegalStateException("floorMaterial of a block the capture did not record");
            },
            () -> walkCaptureReady = false);
        for (Runnable setup : setups) {
            setup.run();
            clearInvocations(trail);
            NavigationService walking = walkService(NavigationConfig.defaults());
            walking.navigate(player, well());
            runSearches();

            assertEquals(NavigationService.DirectLeg.Status.FALLBACK, legStatus(walking));
            ticks(10);
            verify(trail, never()).drawPath(any(), any());
            verify(trail, times(2)).drawDirect(any(), any());
            walking.stop(player);
        }
    }

    @Test
    void anUnreachableTargetFollowsThePartialPath() {
        // §11-5, revised 2026-10-07 (live test A1): the developer prefers a partial path to the straight line
        WalkPath partial = mock(WalkPath.class);
        when(partial.points()).thenReturn(List.of(new double[] {0.5, 64, 0.5}, new double[] {0.5, 64, 6.5},
            new double[] {12.5, 64, 6.5}));
        walkFinder = r -> WalkResult.noPath("target unreachable", 900, partial);
        NavigationService walking = walkService(NavigationConfig.defaults());
        moveTo(40.5, 65, 60.5); // away from the roads, so they cannot help (N13)
        Destination offRoad = Destination.point("Well", NavigationTestNetwork.WORLD, 60.5, 65, 60.5);

        walking.navigate(player, offRoad);
        runSearches();

        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(walking));
        ArgumentCaptor<List<double[]>> drawn = pathCaptor();
        verify(trail).drawPath(any(), drawn.capture());
        assertEquals(3, drawn.getValue().size(), "the partial path only - no straight line on (N8)");
        assertArrayEquals(new double[] {12.5, 64, 6.5}, drawn.getValue().get(2), 1e-9);
        assertTrue(messages().stream().anyMatch(m -> m.contains("No conventional path to Well found")));
        assertTrue(walking.walkStatus().contains("no path 1"), walking.walkStatus());
        assertTrue(walking.walkStatus().contains("partial 1"), walking.walkStatus());

        walkFinder = r -> WalkResult.fallback("expansion budget", 20000, partial);
        NavigationService budget = walkService(NavigationConfig.defaults());
        budget.navigate(player, offRoad);
        runSearches();
        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(budget), "a budget run-out uses it too");
    }

    @Test
    void aResultForAReplacedLegIsDroppedAndItsCaptureCancelled() {
        NavigationService walking = walkService(NavigationConfig.defaults());
        walking.navigate(player, well());
        walking.stop(player);
        runSearches();
        verify(trail, never()).drawPath(any(), any());

        walkCaptureHeld = true;
        walking.navigate(player, well());
        walking.navigate(player, Destination.point("Cart", NavigationTestNetwork.WORLD, 10.5, 65, 10.5));
        assertTrue(walkCaptures.get(1).isCancelled(), "the first leg's capture is cancelled when the leg is replaced");
        walkCaptures.get(1).complete(() -> request(new double[] {0.5, 65, 0.5}, new double[] {20.5, 64, 0.5}, (x, y, z) -> true));
        runSearches();
        verify(trail, never()).drawPath(any(), any());
        assertEquals(NavigationService.DirectLeg.Status.PENDING, legStatus(walking), "the new leg still waits for its own capture");
    }

    @Test
    void theWalkPathIsRecomputedOffThePathAndWhenOldButNeverPerTickOrTwiceInFlight() {
        NavigationService walking = walkService(NavigationConfig.defaults());
        walking.navigate(player, well());
        runSearches();
        ticks(NavigationService.RECHECK_TICKS * 3);
        assertEquals(1, walkTargets.size(), "on the path and younger than 10 s: no recompute");

        moveTo(7.5, 65, 1.5); // 7 blocks beside the path, not heading away along it
        ticks(NavigationService.RECHECK_TICKS);
        assertEquals(2, walkTargets.size(), "more than recompute-distance 6 off the path");
        ticks(NavigationService.RECHECK_TICKS * 2);
        assertEquals(2, walkTargets.size(), "never while a request is in flight");

        walkFinder = r -> found(List.of(new double[] {7.5, 64, 1.5}, new double[] {20.5, 64, 0.5}));
        runSearches();
        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(walking));
        ticks(NavigationService.RECHECK_TICKS * 2);
        assertEquals(2, walkTargets.size(), "the age counts from the request: 8 s");
        ticks(NavigationService.RECHECK_TICKS * 2);
        assertEquals(3, walkTargets.size(), "the path is recomputed once it is chunk-ttl-seconds (10 s) old");
        assertTrue(messages().stream().noneMatch(m -> m.contains("heading away")));
    }

    @Test
    void aGateChangeRecomputesADirectLegAtTheNextRecheck() {
        NavigationService walking = walkService(NavigationConfig.defaults());
        walking.navigate(player, well());
        runSearches();

        walking.onGateChanged(NavigationTestNetwork.GATE_DOOR);
        assertEquals(1, walkTargets.size(), "not at once");
        ticks(NavigationService.RECHECK_TICKS);
        assertEquals(2, walkTargets.size());

        runSearches();
        walking.onAvailabilityChanged();
        ticks(NavigationService.RECHECK_TICKS);
        assertEquals(3, walkTargets.size());
    }

    @Test
    void walkingAlongADetourIsNotHeadingAway() {
        List<double[]> detour = List.of(new double[] {0.5, 64, 0.5}, new double[] {-15.5, 64, 0.5},
            new double[] {-15.5, 64, 20.5}, new double[] {20.5, 64, 20.5}, new double[] {20.5, 64, 0.5});
        walkFinder = r -> found(detour);
        NavigationService walking = walkService(NavigationConfig.defaults());
        walking.navigate(player, well());
        runSearches();

        moveTo(-15.5, 65, 10.5); // 37 blocks from the well, but 26 blocks along the way
        ticks(NavigationService.RECHECK_TICKS);

        assertTrue(messages().stream().noneMatch(m -> m.contains("heading away")), "the detour is the way");
        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(walking));

        moveTo(-15.5, 65, -30.5); // off the path and away from the well
        ticks(NavigationService.RECHECK_TICKS);
        assertTrue(messages().stream().anyMatch(m -> m.contains("heading away from Well")));
        assertEquals(NavigationService.DirectLeg.Status.PENDING, legStatus(walking), "a fresh path is requested");
        assertEquals(2, walkTargets.size());
    }

    @Test
    void theLastLegAfterTheRoadsEndAsksForAPathIntoTheRegion() {
        NavigationService walking = walkService(NavigationConfig.defaults());
        walking.navigate(player, Destination.region("Mill Yard", NavigationTestNetwork.WORLD, MILL_YARD_REGION));
        assertTrue(walkTargets.isEmpty(), "routed: no walk path yet");

        moveTo(20.5, 65, 0.5);
        ticks(1);

        assertTrue(walking.isDirect(playerId));
        assertEquals(1, walkTargets.size(), "the road's end hands over to the same direct leg");
        assertTrue(walkGoals.get(0).reached(30.5, 64, 30.5), "anywhere in the yard arrives");
        assertFalse(walkGoals.get(0).reached(10.5, 64, 10.5));
    }

    @Test
    void theKillSwitchReproducesTodaysStraightLinesExactly() {
        Runnable scenario = () -> {
            moveTo(0.5, 65, 0.5);
            service.navigate(player, well());
            ticks(NavigationService.RECHECK_TICKS);
            moveTo(-20.5, 65, 0.5);
            ticks(NavigationService.RECHECK_TICKS * 2);
            moveTo(19.5, 65, 0.5);
            ticks(1);
        };
        tick.set(100);
        scenario.run();
        List<String> today = transcript();

        clearInvocations(trail, hud, player);
        events.clear();
        tick.set(100);
        service = walkService(NavigationConfig.defaults().withWalk(new NavigationConfig.WalkConfig(false, 20000, 1.75, 96, 48,
            3, 10, 16, 10, 6, 2, List.of("LADDER"), 0.5)));
        scenario.run();

        assertEquals(today, transcript(), "navigation.walk.enabled: false = before KNG-51");
        assertTrue(walkTargets.isEmpty(), "no capture, no search");
        verify(trail, never()).drawPath(any(), any());
        assertTrue(service.walkStatus().startsWith("off"));
    }

    /** Every trail and HUD call with its arguments, the chat lines and the events, in order. */
    private List<String> transcript() {
        List<String> lines = new ArrayList<>();
        for (Object mock : List.of(trail, hud)) {
            mockingDetails(mock).getInvocations().forEach(i -> lines.add(i.getMethod().getName() + " "
                + java.util.Arrays.deepToString(i.getArguments())));
        }
        lines.addAll(messages());
        events.forEach(e -> lines.add(e.getEventName()));
        return lines;
    }
}
