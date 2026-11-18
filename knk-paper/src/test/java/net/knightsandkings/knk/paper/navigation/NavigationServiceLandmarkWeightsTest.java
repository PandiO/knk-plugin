package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.landmarks.LandmarkExtent;
import net.knightsandkings.knk.core.landmarks.LandmarkWeights;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.links.WalkLinkCandidates;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkCellWeights;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;

/**
 * Off-road phase 5.2 (PandiO/knk-workspace#709, DESIGN § 5.4.3, A10): landmark preference weights reach a walk leg's
 * search in {@code preferred} only, with {@code navigation.landmarks} on ({@link NavigationService#setLandmarks}); off,
 * or in another mode, the request is today's. Direct mode: a well 25 blocks from the player, a weighted garden between.
 */
class NavigationServiceLandmarkWeightsTest {

    static final String WORLD = "world";

    private final RoadNetworkSnapshot snapshot = NavigationServiceRouteModeTest.twoWays();
    private final AtomicLong tick = new AtomicLong(100);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private Location feet;
    private final List<WalkRequest> walkRequests = new ArrayList<>();
    private final List<Runnable> walkSearches = new ArrayList<>();
    private NavigationService service;
    private final Destination well = Destination.point("Well", WORLD, 15.5, 65, 20.5);

    /** A garden of radius 4 half way to the well, preference 2.0. */
    private final LandmarkWeights.Area garden = new LandmarkWeights.Area(3,
        new LandmarkWeights.CylinderArea(new LandmarkExtent.Cylinder(8.5, 65, 10.5, 4)), 2.0);

    /** The fake of the model's landmarks (the server's is engine/ModelLandmarks). */
    private final class FakeLandmarks implements NavigationService.Landmarks {
        List<LandmarkWeights.Area> areas = List.of(garden);
        Map<Integer, Integer> nodes = Map.of();
        RuntimeException failure;
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public List<LandmarkWeights.Area> preferenceAreas(String w) {
            asked.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return WORLD.equals(w) ? areas : List.of();
        }

        @Override
        public double preferenceMax() {
            return 4.0;
        }

        @Override
        public Map<Integer, Integer> linkNodes(RoadNetworkSnapshot s) {
            return nodes;
        }
    }

    private final FakeLandmarks landmarks = new FakeLandmarks();

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

    private void runSearches() {
        while (!walkSearches.isEmpty()) {
            List<Runnable> queued = new ArrayList<>(walkSearches);
            walkSearches.clear();
            queued.forEach(Runnable::run);
        }
    }

    private WalkRequest lastWalk(RouteMode mode) {
        walkRequests.clear();
        if (mode == null) {
            service.navigate(player, well);
        } else {
            service.navigate(player, well, mode);
        }
        runSearches();
        assertFalse(walkRequests.isEmpty(), "a walk leg was searched");
        return walkRequests.get(walkRequests.size() - 1);
    }

    @Test
    void inPreferredTheGardenWeightsTheWalkCells() {
        service.setLandmarks(landmarks);

        WalkRequest preferred = lastWalk(null);

        assertNotSame(WalkCellWeights.NONE, preferred.weights(), "the default mode is preferred: weights on");
        assertEquals(2.0, preferred.weights().multiplier(8, 65, 10), "inside the garden");
        assertEquals(1.0, preferred.weights().multiplier(0, 65, 0), "outside");
        assertEquals(1.0, preferred.weights().minMultiplier());
        assertSame(MovementProfile.PLAYER, preferred.profile(), "today's walk profile");
        assertEquals(2.0, lastWalk(RouteMode.PREFERRED).weights().multiplier(8, 65, 10));
    }

    @Test
    void shortestAndEfficientIgnoreTheWeights() {
        service.setLandmarks(landmarks);

        assertSame(WalkCellWeights.NONE, lastWalk(RouteMode.SHORTEST).weights(), "A10: ignored in shortest");
        WalkRequest efficient = lastWalk(RouteMode.EFFICIENT);
        assertSame(WalkCellWeights.NONE, efficient.weights());
        assertSame(MovementProfile.PLAYER, efficient.profile());
    }

    @Test
    void withLandmarksOffTheWalkIsTodays() {
        // navigation.landmarks: false - KnKPlugin sets no Landmarks
        WalkRequest request = lastWalk(null);
        assertSame(WalkCellWeights.NONE, request.weights());
        assertSame(MovementProfile.PLAYER, request.profile());

        service.setLandmarks(landmarks);
        service.setLandmarks(null);
        assertSame(WalkCellWeights.NONE, lastWalk(null).weights(), "switched off again");
        assertEquals(0, landmarks.asked.get(), "the landmarks were never asked");
    }

    @Test
    void aLandmarkFarFromTheLegOrNoneGivesNoWeights() {
        landmarks.areas = List.of(new LandmarkWeights.Area(4,
            new LandmarkWeights.CylinderArea(new LandmarkExtent.Cylinder(900.5, 65, 900.5, 4)), 2.0));
        service.setLandmarks(landmarks);
        assertSame(WalkCellWeights.NONE, lastWalk(null).weights(), "beyond the leg's reach box");

        landmarks.areas = List.of();
        assertSame(WalkCellWeights.NONE, lastWalk(null).weights());
    }

    @Test
    void aFailingLookupMeansNoWeightsNotNoWalk() {
        landmarks.failure = new IllegalStateException("WorldGuard is gone");
        service.setLandmarks(landmarks);

        assertSame(WalkCellWeights.NONE, lastWalk(null).weights());
    }

    @Test
    void forModeAddsTheWeightsInPreferredOnly() {
        WalkRequest request = new WalkRequest(new WalkTerrain(mock(SurfaceGrid.class), GateCells.NONE, WalkCells.NONE),
            CellAccess.OPEN, MovementProfile.PLAYER, 0.5, 65, 0.5, 15.5, 64, 20.5, WalkGoal.within(15.5, 64, 20.5, 1),
            WalkBudget.DEFAULTS);
        LandmarkWeights weights = LandmarkWeights.of(List.of(garden), 4.0);

        assertEquals(2.0, NavigationService.forMode(request, RouteMode.PREFERRED, weights).weights().multiplier(8, 65, 10));
        assertSame(request, NavigationService.forMode(request, RouteMode.EFFICIENT, weights));
        assertSame(request, NavigationService.forMode(request, RouteMode.PREFERRED, LandmarkWeights.NONE));
        assertSame(request, NavigationService.forMode(request, RouteMode.PREFERRED, null));
        WalkRequest shortest = NavigationService.forMode(request, RouteMode.SHORTEST, weights);
        assertSame(WalkCellWeights.NONE, shortest.weights());
        assertEquals(null, NavigationService.forMode(null, RouteMode.PREFERRED, weights));
    }

    @Test
    void landmarkNodesReachTheWalkLinkCandidates() {
        NavigationConfig.WalkLinksConfig config = NavigationConfig.WalkLinksConfig.defaults();
        WalkLinkCandidates without = service.candidatesFor(snapshot, config);
        assertTrue(without.landmarkNodes().isEmpty(), "landmarks off: the candidates of phase 2");

        landmarks.nodes = Map.of(NavigationServiceRouteModeTest.N, 3);
        service.setLandmarks(landmarks);
        WalkLinkCandidates with = service.candidatesFor(snapshot, config);
        assertEquals(Map.of(NavigationServiceRouteModeTest.N, 3), with.landmarkNodes());
        assertSame(with, service.candidatesFor(snapshot, config), "kept while the landmark nodes stay");

        landmarks.nodes = Map.of();
        assertTrue(service.candidatesFor(snapshot, config).landmarkNodes().isEmpty(), "a landmark change makes new ones");
    }
}
