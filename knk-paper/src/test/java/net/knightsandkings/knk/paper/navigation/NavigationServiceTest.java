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
import net.knightsandkings.knk.core.roads.route.GateAvailability;
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
    void noPathABudgetOutAFailedSearchAndAnUncapturedLegAllKeepTheStraightLine() {
        List<Runnable> setups = List.of(
            () -> walkFinder = r -> WalkResult.noPath("unreachable", 9000),
            () -> walkFinder = r -> WalkResult.fallback("expansion budget", 20000),
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
    void anUnreachableTargetFollowsThePartialPathThenAStraightLine() {
        // §11-5, revised 2026-10-07 (live test A1): the developer prefers a partial path to the straight line
        WalkPath partial = mock(WalkPath.class);
        when(partial.points()).thenReturn(List.of(new double[] {0.5, 64, 0.5}, new double[] {0.5, 64, 6.5},
            new double[] {12.5, 64, 6.5}));
        walkFinder = r -> WalkResult.noPath("target unreachable", 900, partial);
        NavigationService walking = walkService(NavigationConfig.defaults());

        walking.navigate(player, well());
        runSearches();

        assertEquals(NavigationService.DirectLeg.Status.WALKING, legStatus(walking));
        ArgumentCaptor<List<double[]>> drawn = pathCaptor();
        verify(trail).drawPath(any(), drawn.capture());
        assertEquals(4, drawn.getValue().size(), "the partial path, then the target");
        assertArrayEquals(new double[] {12.5, 64, 6.5}, drawn.getValue().get(2), 1e-9);
        assertArrayEquals(new double[] {20.5, 64, 0.5}, drawn.getValue().get(3), 1e-9, "the straight rest ends at the target");
        assertTrue(walking.walkStatus().contains("no path 1"), walking.walkStatus());
        assertTrue(walking.walkStatus().contains("partial 1"), walking.walkStatus());

        walkFinder = r -> WalkResult.fallback("expansion budget", 20000, partial);
        NavigationService budget = walkService(NavigationConfig.defaults());
        budget.navigate(player, well());
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
