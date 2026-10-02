package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import net.knightsandkings.knk.core.roads.route.GateAvailability.GateView;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.route.StaticFlagsAvailability;
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

        NavigationService.PolicyFactory policies = (p, snapshot) -> {
            GateAvailability.GateState gates = doorId -> Optional.ofNullable(gateStates.get(doorId))
                .map(state -> new GateView(doorId, "West Gate", state, false, false, false, false, false));
            DomainAvailability.DomainLookup lookup = regionId -> NavigationTestNetwork.CASTLE_REGION.equals(regionId)
                ? Optional.of(new DomainSnapshot(NavigationTestNetwork.CASTLE_DOMAIN, "Kardenna Castle", "", regionId,
                    !castleDenied.get(), true, "Structure", Set.of(), Set.of(), Set.of(), Set.of()))
                : Optional.empty();
            return CompositeAccessPolicy.of(new StaticFlagsAvailability(), new GateAvailability(gates, doorId -> false),
                new DomainAvailability(new DomainAccessEvaluator(), lookup, Set.of(), false));
        };
        RegionShapes shapes = (w, id) -> switch (id) {
            case NavigationTestNetwork.CASTLE_REGION -> Optional.of(RegionShape.cuboid(190, 60, 190, 210, 80, 210));
            case MILL_YARD_REGION -> Optional.of(RegionShape.cuboid(20, 60, 20, 40, 80, 40));
            default -> Optional.empty();
        };
        NavigationEligibility eligibility = new NavigationEligibility(uuid -> false, uuid -> false, uuid -> inSiege.get());
        service = new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            w -> network.snapshot, policies, shapes, eligibility, hud, trail, Runnable::run, Runnable::run, tick::get,
            events::add, Logger.getLogger("test")));
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
    void theSafetyNetRecheckCatchesAGateClosedWithoutAnEvent() {
        service.navigate(player, cinixKeep());
        gateStates.put(NavigationTestNetwork.GATE_DOOR, AnimationState.CLOSED);

        ticks(NavigationService.RECHECK_TICKS + 1);

        assertDetour(service.sessionOf(playerId).orElseThrow());
    }

    @Test
    void aDeniedDestinationDomainGivesAPartialRouteToItsEdge() {
        castleDenied.set(true);

        service.navigate(player, Destination.point("Kardenna Castle", NavigationTestNetwork.WORLD, 200.5, 65, 200.5));

        NavigationSession session = service.sessionOf(playerId).orElseThrow();
        assertTrue(session.explanation().isPresent());
        assertTrue(session.explanation().get().isDomainBlock());
        assertTrue(messages().stream().anyMatch(m -> m.contains("You may not enter Kardenna Castle") && m.contains("Guiding you to its edge")));
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
}
