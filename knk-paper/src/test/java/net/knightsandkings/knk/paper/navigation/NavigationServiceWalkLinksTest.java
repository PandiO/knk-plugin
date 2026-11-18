package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.links.WalkLinkCache;
import net.knightsandkings.knk.core.roads.links.WalkLinkCounters;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Off-road navigation phase 2 (#705): walk links and the road-first rule through the runtime. The courtyard network:
 * the market S (-60, 64, 0) - A (0, 64, 0), round the courtyard A - P (0, -30) - Q (20, -30) - B (20, 0), then B - T
 * (80, 64, 0), the keep. Routing and the main thread run inline; walk searches wait for {@link #runSearches}; the fake
 * search walks straight from its start to its target.
 */
class NavigationServiceWalkLinksTest {

    static final String WORLD = "world";
    static final int S = 1, A = 2, P = 3, Q = 4, B = 5, T = 6;

    private final RoadNetworkSnapshot snapshot = courtyard();
    private final AtomicLong tick = new AtomicLong(100);
    private final AtomicLong clock = new AtomicLong(0);
    private final List<Event> events = new ArrayList<>();
    private final NavigationHud hud = mock(NavigationHud.class);
    private final TrailRenderer trail = mock(TrailRenderer.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private Location feet;
    private final List<double[]> walkTargets = new ArrayList<>();
    private final List<Runnable> walkSearches = new ArrayList<>();
    /** The search's answer for a request; by default straight from the start to the target, its length as walked. */
    private Function<WalkRequest, WalkResult> walkFinder = NavigationServiceWalkLinksTest::straight;
    private NavigationService service;
    private WalkLinkCache cache;
    private WalkLinkCounters counters;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(WORLD);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Pandi");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> feet.clone());
        when(trail.periodTicks()).thenReturn(10);
        feet = new Location(world, -59.5, 65, 0.5);
    }

    static RoadNetworkSnapshot courtyard() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addStreet(new RoadNetworkSnapshot.Street(1, "Keepstreet"));
        b.addNode(new RoadNode(S, -60, 64, 0, RoadNodeKind.ENDPOINT, "Market", 1));
        b.addNode(new RoadNode(A, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(P, 0, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(Q, 20, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(B, 20, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(T, 80, 64, 0, RoadNodeKind.ENDPOINT, "Keep", 1));
        b.addEdge(edge(11, S, A, -60, 0, 0, 0));
        b.addEdge(edge(12, A, P, 0, 0, 0, -30));
        b.addEdge(edge(13, P, Q, 0, -30, 20, -30));
        b.addEdge(edge(14, Q, B, 20, -30, 20, 0));
        b.addEdge(edge(15, B, T, 20, 0, 80, 0));
        return b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int z0, int x1, int z1) {
        return new RoadEdge(id, from, to, List.of(new int[] {x0, 64, z0}, new int[] {x1, 64, z1}),
            Math.hypot(x1 - x0, z1 - z0), 3, OptionalInt.of(1), OptionalInt.of(1), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    /** A found path straight from the request's start to its target, block centres a block apart. */
    static WalkResult straight(WalkRequest r) {
        double sx = Math.floor(r.startX()), sz = Math.floor(r.startZ());
        double tx = Math.floor(r.targetX()), tz = Math.floor(r.targetZ());
        double length = Math.max(1, Math.hypot(tx - sx, tz - sz));
        List<double[]> points = new ArrayList<>();
        int n = (int) Math.ceil(length);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            points.add(new double[] {Math.floor(sx + (tx - sx) * t) + 0.5, 64, Math.floor(sz + (tz - sz) * t) + 0.5});
        }
        return found(points, length);
    }

    static WalkResult found(List<double[]> points, double length) {
        WalkPath path = mock(WalkPath.class);
        when(path.points()).thenReturn(points);
        when(path.length()).thenReturn(length);
        when(path.cost()).thenReturn(length);
        return WalkResult.found(path, 42);
    }

    private NavigationService service(NavigationConfig config, boolean links) {
        NavigationService.WalkPreparer preparer = (p, from, target, goal, destinationRegions) -> {
            walkTargets.add(target.clone());
            Supplier<WalkRequest> request = () -> new WalkRequest(new WalkTerrain(mock(SurfaceGrid.class), GateCells.NONE,
                WalkCells.NONE), CellAccess.OPEN, MovementProfile.PLAYER, from[0], from[1], from[2], target[0], target[1],
                target[2], goal, WalkBudget.DEFAULTS);
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
        service = new NavigationService(new NavigationService.Deps(null, config, w -> snapshot, policies,
            (w, id) -> Optional.empty(), new NavigationEligibility(u -> false, u -> false, u -> false), hud, trail,
            Runnable::run, Runnable::run, tick::get, events::add, Logger.getLogger("test"),
            new NavigationService.Walk(preparer, request -> walkFinder.apply(request), walkSearches::add)));
        if (links) {
            cache = new WalkLinkCache(clock::get, 600, 600, 100);
            counters = new WalkLinkCounters();
            service.setWalkLinks(new NavigationService.WalkLinks(config.walkLinks(), cache, counters, null));
        }
        return service;
    }

    private static NavigationConfig linksOn() {
        return NavigationConfig.defaults().withWalkLinks(NavigationConfig.WalkLinksConfig.defaults().withEnabled(true));
    }

    private void runSearches() {
        while (!walkSearches.isEmpty()) {
            List<Runnable> queued = new ArrayList<>(walkSearches);
            walkSearches.clear();
            queued.forEach(Runnable::run);
        }
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick.incrementAndGet();
            service.tickAll();
        }
    }

    private void moveTo(double x, double z) {
        feet = new Location(world, x, 65, z);
    }

    private boolean said(String text) {
        return messages().stream().anyMatch(m -> m.endsWith(text));
    }

    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    /** Every trail and HUD call with its arguments (paths written out), the chat lines and the events, in order. */
    private List<String> transcript() {
        List<String> lines = new ArrayList<>();
        for (Object m : List.of(trail, hud)) {
            mockingDetails(m).getInvocations().forEach(i -> {
                StringBuilder sb = new StringBuilder(i.getMethod().getName());
                for (Object arg : i.getArguments()) {
                    sb.append(' ').append(arg instanceof List<?> list ? Arrays.deepToString(list.toArray())
                        : arg instanceof Route r ? r + " " + r.length() : Arrays.deepToString(new Object[] {arg}));
                }
                lines.add(sb.toString());
            });
        }
        lines.addAll(messages());
        events.forEach(e -> lines.add(e.getEventName()));
        return lines;
    }

    private Route route() {
        return service.sessionOf(playerId).orElseThrow().route().orElseThrow();
    }

    /** From the market to the keep, the whole way along the road, then a direct walk to a spot near the market. */
    private void scenario() {
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        runSearches();
        for (int x = -59; x <= 0; x += 2) {
            moveTo(x + 0.5, 0.5);
            ticks(2);
        }
        runSearches();
        service.stop(player);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Well", WORLD, -40.5, 65, 6.5));
        runSearches();
        ticks(NavigationService.RECHECK_TICKS);
        runSearches();
    }

    @Test
    void withTheFlagOffNothingChanges() {
        // A16-style transcript: navigation.walk-links false (no WalkLinks set, as KnKPlugin does) - the walk-link keys
        // changed or not, routes, trail, HUD, chat and events are today's call for call, and no link search runs
        service(NavigationConfig.defaults(), false);
        scenario();
        List<String> today = transcript();
        List<double[]> todaysSearches = new ArrayList<>(walkTargets);

        clearInvocations(trail, hud, player);
        events.clear();
        walkTargets.clear();
        tick.set(100);
        service(NavigationConfig.defaults().withWalkLinks(new NavigationConfig.WalkLinksConfig(false, 10, 1.5, 5, 5, 8,
            0.1, 9)), false);
        scenario();

        assertEquals(today, transcript(), "navigation.walk-links: false = before #705");
        assertEquals(todaysSearches.size(), walkTargets.size(), "the same walk searches, no link search");
        assertTrue(service.walkLinkStatusLines().isEmpty());
        assertTrue(service.walkLinks().isEmpty());
        assertTrue(today.stream().anyMatch(l -> l.contains("Well is")), "the second navigation is direct: " + today);
    }

    @Test
    void aFoundLinkGivesTheShortcutAcrossTheCourtyard() {
        // DESIGN § 5.2.2: the first route is the road's (200 blocks); the link A -> B is searched off the main thread;
        // once found, the session takes the better route: road, link, road (142 blocks), and says so
        service(linksOn(), true);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        assertEquals(200, route().length(), 0.6, "today's route first (the player stands half a block along)");
        assertTrue(walkTargets.stream().anyMatch(t -> t[0] == 20 && t[2] == 0), "a link search to B");
        assertEquals(1, cache.counters().pending());

        runSearches();
        Route route = route();
        assertTrue(route.hasWalkLinks(), route.toString());
        assertEquals(60 + 20 + 60, route.length(), 0.6);
        List<Route.Leg> legs = route.legs();
        assertInstanceOf(Route.RoadLeg.class, legs.get(0));
        assertInstanceOf(Route.WalkLinkLeg.class, legs.get(1));
        assertInstanceOf(Route.RoadLeg.class, legs.get(2));
        assertTrue(said("A shorter route opened - following it now."), messages().toString());
        assertEquals(1, cache.counters().cachedFound());
        assertEquals(1, counters.searched());
        assertTrue(service.walkLinkStatusLines().get(0).contains("1 with links (1 links)"),
            service.walkLinkStatusLines().toString());
        assertTrue(service.sessionOf(playerId).orElseThrow().maneuvers().stream()
            .anyMatch(m -> m.text().equals("Leave the road and walk across")));

        // the trail is drawn on the route with the link (the walk link's spots in the leg colour, TrailRenderer)
        ArgumentCaptor<Route> drawn = ArgumentCaptor.forClass(Route.class);
        verify(trail, atLeastOnce()).drawRoute(org.mockito.ArgumentMatchers.any(), drawn.capture(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());
        assertTrue(drawn.getAllValues().get(drawn.getAllValues().size() - 1).hasWalkLinks());
    }

    @Test
    void aRouteFromAFieldOverTheShortcutIsWalkRoadWalkRoadWalk() {
        // A02: from a field 15 blocks off the market road to a door 12 blocks off the keep's road end
        service(linksOn(), true);
        moveTo(-59.5, 15.5);
        service.navigate(player, Destination.point("Keep door", WORLD, 80.5, 65, 12.5));
        runSearches();
        Route route = route();
        List<String> kinds = new ArrayList<>();
        kinds.add(service.startLegOf(playerId).isPresent() ? "walk" : "-");
        route.legs().forEach(l -> kinds.add(l instanceof Route.WalkLinkLeg ? "walk" : "road"));
        double[] end = route.end().point();
        kinds.add(NavigationService.distance(end[0], end[1], end[2], new double[] {80.5, 64, 12.5}) > 4 ? "walk" : "-");
        assertEquals(List.of("walk", "road", "walk", "road", "walk"), kinds, route.toString());
    }

    @Test
    void aBlockChangeDropsTheLinkAndTheNextRouteSearchesAgain() {
        // A05
        service(linksOn(), true);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        runSearches();
        assertTrue(route().hasWalkLinks());
        int searches = walkTargets.size();

        cache.terrainChanged(WORLD, 0, 0); // a wall built in the courtyard
        assertEquals(0, cache.counters().cachedFound());
        service.stop(player);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        assertFalse(route().hasWalkLinks(), "the road again");
        assertEquals(searches + 1, walkTargets.size(), "searched again");
    }

    @Test
    void aLinkSearchThatFindsNoWayKeepsTheRoad() {
        service(linksOn(), true);
        walkFinder = r -> r.targetX() == 20 && r.targetZ() == 0 ? WalkResult.noPath("closed", 300) : straight(r);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        runSearches();
        assertFalse(route().hasWalkLinks());
        assertEquals(1, cache.counters().cachedNone());
        assertFalse(said("A shorter route opened - following it now."));
    }

    @Test
    void roadFirstTakesTheRoadThatCarriesMostOfTheWay() {
        // the developer's wish (#698, 2026-11-18, scene 7): a target 35 blocks along the market road, 3 off it, is within
        // max-snap-distance; one walk path reaches it, but the road carries most of the way at a similar length
        service(linksOn(), true);
        moveTo(-49.5, 3.5);
        service.navigate(player, Destination.point("Stall", WORLD, -9.5, 65, 3.5));
        assertTrue(service.isDirect(playerId), "direct mode first, as today");
        runSearches();
        assertFalse(service.isDirect(playerId), "the road instead");
        assertTrue(said("Following the roads to Stall - they carry most of the way."), messages().toString());
        assertEquals(1, counters.roadFirstRoad());
    }

    @Test
    void roadFirstKeepsTheWalkWhenTheRoadGoesTheLongWayRound() {
        // across the courtyard: 16 blocks on foot, 80 round it by road
        service(linksOn(), true);
        moveTo(2.5, 2.5);
        service.navigate(player, Destination.point("Well", WORLD, 18.5, 65, 2.5));
        runSearches();
        assertTrue(service.isDirect(playerId), "the walk path");
        assertTrue(service.legOf(playerId).orElseThrow().walking());
        assertEquals(1, counters.roadFirstWalk());
    }

    @Test
    void theRoadTrailKeepsTheLeadUntilTheRoadsEnd() {
        // coordinator note (2026-11-18, the developer's scene 3): with walk links on, the last walk leg starts at the
        // road's end, never while the player is still on the road; the courtyard link is part of the route, not a leg
        service(linksOn(), true);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep door", WORLD, 80.5, 65, 12.5));
        runSearches();
        assertTrue(route().hasWalkLinks());
        for (int x = -59; x <= 74; x++) { // the road ends at 80; within arrive-distance (4) of it is its end
            moveTo(x + 0.5, 0.5);
            ticks(1);
            assertFalse(service.isDirect(playerId), "still on the route at x " + x);
            assertTrue(service.sessionOf(playerId).isPresent());
        }
        moveTo(80.5, 0.5);
        ticks(1);
        assertTrue(service.isDirect(playerId), "the last walk from the road's end");
    }

    @Test
    void anNpcRoadPlanNeverTakesAWalkLink() {
        // coordinator note (NPC chain, 2026-11-18): NPCs learn walk links in a later NPC phase; with the flag on their
        // road plans (planRoadFor, the NPC module's port) stay on today's router - only a player's route passes links
        service(linksOn(), true);
        moveTo(-59.5, 0.5);
        service.navigate(player, Destination.point("Keep", WORLD, 80.5, 65, 0.5));
        runSearches();
        assertTrue(route().hasWalkLinks(), "the player has the link");
        var npc = new net.knightsandkings.knk.core.npc.nav.NpcAccessSubject("guard-1",
            net.knightsandkings.knk.core.npc.nav.NpcAccessProfile.DEFAULT, WORLD, java.util.Set.of());
        var plan = service.planRoadFor(npc, new net.knightsandkings.knk.core.navigation.shape.NavPoint(WORLD, -60, 64, 0),
            new net.knightsandkings.knk.core.navigation.shape.NavPoint(WORLD, 80, 64, 0)).join();
        assertEquals(net.knightsandkings.knk.core.npc.nav.RoadPlanner.Status.FOUND, plan.status());
        assertTrue(plan.line().stream().anyMatch(p -> p.z() <= -29), "round the courtyard by road: " + plan.line());
        assertEquals(1, cache.counters().cachedFound(), "no link searched for the NPC");
    }

        @Test
    void withTheFlagOffDirectModeStaysAsToday() {
        service(NavigationConfig.defaults(), false);
        moveTo(-49.5, 3.5);
        service.navigate(player, Destination.point("Stall", WORLD, -9.5, 65, 3.5));
        runSearches();
        assertTrue(service.isDirect(playerId), "no road-first rule without walk links");
        assertTrue(service.legOf(playerId).orElseThrow().walking());
        assertFalse(said("Following the roads to Stall - they carry most of the way."));
    }
}
