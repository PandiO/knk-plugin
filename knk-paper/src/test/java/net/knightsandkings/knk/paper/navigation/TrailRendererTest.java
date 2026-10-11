package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.utils.TickBudget;

/** Trail range and spacing (DESIGN §6.4), the off-road legs, and the lag fallback. */
class TrailRendererTest {

    private final NavigationTestNetwork network = new NavigationTestNetwork();

    @Test
    void trailPointsCoverTheWindowAtTheSpacing() {
        Route route = network.routeAlongMainStreet(); // A(0,64,0) → B(100,64,0) → C(200,64,0)
        List<double[]> points = TrailRenderer.trailPoints(route, 10, 30, 1.5);

        assertEquals(21, points.size(), "30 blocks / 1.5 + the window's end");
        assertEquals(10.5, points.get(0)[0], 1e-9, "block centre of the first point");
        assertEquals(64, points.get(0)[1], 1e-9, "floor y");
        assertEquals(40.5, points.get(points.size() - 1)[0], 1e-9);
        for (int i = 1; i < points.size() - 1; i++) {
            assertEquals(1.5, points.get(i)[0] - points.get(i - 1)[0], 1e-9);
        }
    }

    @Test
    void trailPointsStopAtTheRouteEnd() {
        Route route = network.routeAlongMainStreet();
        List<double[]> points = TrailRenderer.trailPoints(route, 190, 30, 1.5);

        assertEquals(200.5, points.get(points.size() - 1)[0], 1e-9, "the route's end, not beyond");
        assertTrue(points.size() <= 8);
        assertTrue(TrailRenderer.trailPoints(Route.empty(route.start()), 0, 30, 1.5).isEmpty());
    }

    /** Main Street (z = 0): a road two rows wide (z 0 and 1) up to x 60, one row after - the centred trail shifts, then not. */
    private static net.knightsandkings.knk.core.roads.route.TrailCentring.Ground narrowingRoad() {
        return new net.knightsandkings.knk.core.roads.route.TrailCentring.Ground() {
            @Override
            public java.util.OptionalInt roadFloor(int x, int z, int nearY) {
                boolean road = z == 0 || (z == 1 && x < 60);
                return road && Math.abs(nearY - 64) <= 1 ? java.util.OptionalInt.of(64) : java.util.OptionalInt.empty();
            }

            @Override
            public boolean stairOrSlab(int x, int y, int z) {
                return false;
            }
        };
    }

    @Test
    void aRedrawPutsEveryParticleOfTheCentredTrailWhereItWas() {
        // KNG-76 live test: the centred trail twitched in front of the player as the window slid with them
        Route route = network.routeAlongMainStreet();
        List<double[]> before = TrailRenderer.centredWindow(route, 58.0, 30, 1.5, narrowingRoad());
        List<double[]> after = TrailRenderer.centredWindow(route, 59.3, 30, 1.5, narrowingRoad());

        assertEquals(58.5 + 0.5, before.get(0)[0], 1e-9, "the first fixed spot at or ahead of the player, block-centred");
        assertEquals(60 + 0.5, after.get(0)[0], 1e-9);
        int shared = 0;
        for (double[] p : after) {
            for (double[] q : before) {
                if (Math.abs(p[0] - q[0]) < 1e-9) {
                    assertEquals(q[2], p[2], 1e-9, "the same spot, the same place at x " + p[0]);
                    shared++;
                }
            }
        }
        assertTrue(shared >= 18, "most of the window is shared: " + shared);
        assertTrue(before.get(0)[2] > 0.5, "pulled towards the second row just before x 60 (smoothed)");
        assertEquals(0.5, after.get(after.size() - 1)[2], 1e-9, "the one row after");
    }

    @Test
    void theCentredWindowEndsAtTheRoutesEnd() {
        Route route = network.routeAlongMainStreet();
        List<double[]> tail = TrailRenderer.centredWindow(route, 190, 30, 1.5, narrowingRoad());

        assertEquals(200.5, tail.get(tail.size() - 1)[0], 1e-9);
        assertTrue(TrailRenderer.centredWindow(Route.empty(route.start()), 0, 30, 1.5, narrowingRoad()).isEmpty());
    }

    @Test
    void legPointsAreStraightAndSparse() {
        List<double[]> points = TrailRenderer.legPoints(new double[] {0, 64, 0}, new double[] {10, 64, 0}, 3);

        assertEquals(5, points.size(), "0, 3, 6, 9 and the end");
        assertEquals(9, points.get(3)[0], 1e-9);
        assertEquals(10, points.get(4)[0], 1e-9);
        assertEquals(1, TrailRenderer.legPoints(new double[] {1, 2, 3}, new double[] {1, 2, 3}, 3).size());
    }

    // ---- KNG-110: a region the player may not enter over part of the road -----------------------------

    /** Main Street three rows wide (z -1..1); district_9 over rows 0 and 1 for x 0-60, the centre line included. */
    private final java.util.concurrent.atomic.AtomicInteger regionLookups = new java.util.concurrent.atomic.AtomicInteger();
    private long now;

    private TrailRenderer districtTrail(java.util.function.Predicate<String> mayEnter) {
        net.knightsandkings.knk.core.roads.route.TrailCentring.Ground road =
            new net.knightsandkings.knk.core.roads.route.TrailCentring.Ground() {
                @Override
                public java.util.OptionalInt roadFloor(int x, int z, int nearY) {
                    return Math.abs(z) <= 1 ? java.util.OptionalInt.of(64) : java.util.OptionalInt.empty();
                }

                @Override
                public boolean stairOrSlab(int x, int y, int z) {
                    return false;
                }
            };
        TrailRenderer.CellRegions regions = (world, x, feetY, z) -> {
            regionLookups.incrementAndGet();
            return x >= 0 && x <= 60 && z >= 0 && z <= 1 && feetY == 65 ? java.util.Set.of("town_1", "district_9")
                : java.util.Set.of("town_1");
        };
        return new TrailRenderer(NavigationConfig.TrailConfig.defaults(), new TickBudget(() -> 20.0), w -> road, regions,
            player -> mayEnter, () -> now);
    }

    private static Player viewerIn(World world) {
        Player viewer = mock(Player.class);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        return viewer;
    }

    @Test
    void theTrailKeepsToTheFreeRowBesideARegionThePlayerMayNotEnter() {
        Player viewer = viewerIn(mock(World.class));
        TrailRenderer renderer = districtTrail(regionId -> !regionId.equals("district_9"));

        net.knightsandkings.knk.core.roads.route.TrailCentring.Ground surface = renderer.surfaceFor(viewer);
        assertTrue(surface.blocked(10, 64, 0) && surface.blocked(10, 64, 1));
        assertTrue(!surface.blocked(10, 64, -1), "the free row");

        List<double[]> window = TrailRenderer.centredWindow(network.routeAlongMainStreet(), 10, 20, 1.5, surface);
        window.forEach(p -> assertEquals(-0.5, p[2], 1e-9, "on row z = -1, past the district"));
    }

    @Test
    void aPlayerWhoMayEnterOrIsNotNavigatingKeepsTheMiddle() {
        Player viewer = viewerIn(mock(World.class));

        net.knightsandkings.knk.core.roads.route.TrailCentring.Ground none = districtTrail(null).surfaceFor(viewer);
        assertTrue(!none.blocked(10, 64, 0), "no rule: the world's surface as it is");
        assertEquals(0, regionLookups.get(), "and no region lookups");

        net.knightsandkings.knk.core.roads.route.TrailCentring.Ground bypass = districtTrail(regionId -> true).surfaceFor(viewer);
        assertTrue(!bypass.blocked(10, 64, 0));
        TrailRenderer.centredWindow(network.routeAlongMainStreet(), 10, 20, 1.5, bypass)
            .forEach(p -> assertEquals(0.5, p[2], 1e-9));
    }

    @Test
    void theRegionsOfACellAreRememberedForAFewSeconds() {
        Player viewer = viewerIn(mock(World.class));
        TrailRenderer renderer = districtTrail(regionId -> !regionId.equals("district_9"));

        renderer.surfaceFor(viewer).blocked(10, 64, 0);
        renderer.surfaceFor(viewer).blocked(10, 64, 0);
        assertEquals(1, regionLookups.get());

        now += TrailRenderer.REGION_CACHE_MILLIS + 1;
        renderer.surfaceFor(viewer).blocked(10, 64, 0);
        assertEquals(2, regionLookups.get(), "asked again after the cache's time");
    }

    @Test
    void drawRouteSpawnsParticlesOnlyForTheNavigatingPlayer() {
        World world = mock(World.class);
        Player viewer = mock(Player.class);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 0.5, 65, 0.5));
        when(world.getName()).thenReturn("world");
        TrailRenderer renderer = new TrailRenderer(NavigationConfig.TrailConfig.defaults(), new TickBudget(() -> 20.0));

        renderer.drawRoute(viewer, network.routeAlongMainStreet(), 0, null);

        verify(viewer, atLeast(20)).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void aLaggingServerDrawsHalfTheTrail() {
        World world = mock(World.class);
        Player viewer = mock(Player.class);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 0.5, 65, 0.5));
        TrailRenderer lagging = new TrailRenderer(NavigationConfig.TrailConfig.defaults(), new TickBudget(() -> 10.0));

        lagging.drawRoute(viewer, network.routeAlongMainStreet(), 0, null);

        verify(viewer, never()).spawnParticle(eq(Particle.DUST), eq(20.5), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    /** An L-shaped walk path: (0.5, 64, 0.5) → 10 blocks north → 20 blocks east (KNG-51 floor points). */
    private static List<double[]> lPath() {
        return List.of(new double[] {0.5, 64, 0.5}, new double[] {0.5, 64, 10.5}, new double[] {20.5, 64, 10.5});
    }

    @Test
    void projectFindsTheNearestPointAndHowFarAlongItIs() {
        double[] at = TrailRenderer.project(lPath(), new double[] {3.5, 64, 5.5});
        assertEquals(5, at[0], 1e-9, "5 blocks along the first segment");
        assertEquals(3, at[1], 1e-9, "3 blocks beside it");

        double[] corner = TrailRenderer.project(lPath(), new double[] {10.5, 64, 12.5});
        assertEquals(20, corner[0], 1e-9, "10 north + 10 east");
        assertEquals(2, corner[1], 1e-9);

        assertEquals(0, TrailRenderer.project(List.of(new double[] {1, 2, 3}), new double[] {1, 2, 3})[1], 1e-9);
        assertEquals(30, TrailRenderer.polylineLength(lPath()), 1e-9);
        assertEquals(5.5, TrailRenderer.pointAt(lPath(), 15)[0], 1e-9, "5 blocks past the corner");
        assertEquals(20.5, TrailRenderer.pointAt(lPath(), 99)[0], 1e-9, "clamped to the end");
    }

    @Test
    void aPathWindowStartsAtThePlayersProjectionAndFollowsTheCorner() {
        List<double[]> window = TrailRenderer.pathWindow(lPath(), new double[] {2.5, 64, 6.5}, 12, 3);

        assertEquals(5, window.size(), "6, 9, 12, 15 along and the window's end at 18");
        assertEquals(0.5, window.get(0)[0], 1e-9);
        assertEquals(6.5, window.get(0)[2], 1e-9, "from the projection, not the path's start");
        assertEquals(2.5, window.get(2)[0], 1e-9, "12 along = 2 blocks past the corner");
        assertEquals(10.5, window.get(2)[2], 1e-9);
        assertEquals(8.5, window.get(4)[0], 1e-9, "18 along");
        assertEquals(64, window.get(4)[1], 1e-9, "floor y kept - the points are floor cells already");

        List<double[]> nearTheEnd = TrailRenderer.pathWindow(lPath(), new double[] {19.5, 64, 10.5}, 12, 3);
        assertEquals(20.5, nearTheEnd.get(nearTheEnd.size() - 1)[0], 1e-9, "never past the path's end");
        assertTrue(TrailRenderer.pathWindow(List.of(), new double[] {0, 0, 0}, 12, 3).isEmpty());
    }

    @Test
    void drawPathUsesTheLegColourWithoutWorldReads() {
        World world = mock(World.class);
        Player viewer = mock(Player.class);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 0.5, 65, 0.5));
        TrailRenderer renderer = new TrailRenderer(NavigationConfig.TrailConfig.defaults(), new TickBudget(() -> 20.0));

        renderer.drawPath(viewer, lPath());

        verify(viewer, atLeast(10)).spawnParticle(eq(Particle.DUST), anyDouble(), eq(64 + TrailRenderer.LIFT), anyDouble(),
            anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void unknownParticleNamesFallBackToDust() {
        assertEquals(Particle.DUST, TrailRenderer.particleOf("nonsense"));
        assertEquals(Particle.FLAME, TrailRenderer.particleOf("flame"));
    }

    @Test
    void snapPointsAreFloorPoints() {
        SnapPoint start = network.routeAlongMainStreet().start();
        assertEquals(64, start.y(), 1e-9);
    }
}
