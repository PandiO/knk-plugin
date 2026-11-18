package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.World;

import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkPath;
import net.knightsandkings.knk.core.roads.walk.WalkPathShaper;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.walk.CapturedWalkTerrain;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunk;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunkExtractor;
import net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.roads.ChunkSnapshotSurfaceGrid;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Off-road navigation phase 6.1 (PandiO/knk-workspace#710, DESIGN § 7.1, § 13 A15, A16, A27) on real blocks, without
 * Citizens: the player's walk path from the street east of the developer's stair house up all five flights to the top
 * hall. The box is captured with the walk capture's own code ({@link WalkChunkExtractor}); the search and the shaping
 * run on another thread from that capture only. Checks: a clearance cost of 0 gives today's search exactly; the shaped
 * trail stays over walkable floor, never comes nearer a wall, post or drop than the shaper's hard minimum (measured on
 * the live blocks, not on the capture), keeps {@code wall-clearance} within the shared shaper's tolerance, and has
 * fewer of its points under it than today's path (the flag off).
 */
public final class WalkTrailStairhouseScenario implements Scenario<ScenarioScene>, SizedScene {

    /** Spacing of the measured points along a line. */
    static final double SAMPLE = 0.25;
    /** The shared shaper's tolerance under {@code wall-clearance} (its string pulling, see the core tests). */
    static final double TOLERANCE = 0.1;

    @Override
    public String name() {
        return "walk-trail-stairhouse";
    }

    @Override
    public String description() {
        return "A player's walk trail up the stair house, shaped off the main thread with guidance-v2: over the floor, "
            + "clear of the walls, wider than the cells";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcMovementScene.STAIRHOUSE_BOX;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene);
        NavigationConfig.WalkConfig walk = NavigationConfig.WalkConfig.defaults();
        BukkitNavWorld live = new BukkitNavWorld(null);
        String w = scene.world().getName();
        AtomicReference<WalkTerrain> terrain = new AtomicReference<>();
        AtomicReference<WalkPath> cells = new AtomicReference<>();
        AtomicReference<WalkPathShaper.Shaped> shaped = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        plan.act("build the stair house", house::build)
            .act("capture the box as a walk leg does", () -> terrain.set(capture(scene, walk)))
            .check("a clearance cost of 0 searches exactly as today (guidance-v2 off)", () -> {
                WalkRequest request = request(house, terrain.get(), walk, walk.profile(false));
                WalkResult today = new WalkSearch(() -> 0L).find(request);
                WalkResult zero = new WalkSearch(() -> 0L).find(
                    request.withProfile(walk.profile(true).withClearance(0, walk.wallClearance())));
                if (!today.isFound()) {
                    return "today's search finds no way up: " + today;
                }
                return today.toString().equals(zero.toString()) && today.stats().equals(zero.stats()) ? null
                    : "the searches differ: " + today + " / " + zero;
            })
            .check("with guidance-v2 the path is found and shaped on another thread from the capture only", () -> {
                WalkRequest request = request(house, terrain.get(), walk, walk.profile(true));
                CompletableFuture.runAsync(() -> {
                    thread.set(Thread.currentThread().getName());
                    WalkResult result = new WalkSearch().find(request);
                    result.path().ifPresent(path -> {
                        cells.set(path);
                        shaped.set(new WalkPathShaper().shape(path, request.terrain(), request.profile(),
                            request.access(), walk.shaping()));
                    });
                }).join();
                if (cells.get() == null) {
                    return "no way up with the clearance cost";
                }
                if (thread.get().equals(Thread.currentThread().getName())) {
                    return "shaped on the main thread";
                }
                return shaped.get() == null || shaped.get().trail().size() < 2 ? "no shaped trail" : null;
            })
            .check("every point of the trail is over walkable floor within a step of its height", () -> {
                for (double[] p : samples(shaped.get().trail())) {
                    if (!overFloor(live, w, p)) {
                        return "off the floor at " + describe(p) + " (" + house.realBlock(feet(w, p)) + ")";
                    }
                }
                return null;
            })
            .check("the trail never comes nearer a wall, post or drop than the hard minimum (live blocks)", () -> {
                for (double[] p : samples(shaped.get().trail())) {
                    double d = clearance(live, w, p);
                    if (d < CorridorShaper.HARD_MINIMUM) {
                        return String.format(Locale.ROOT, "%.2f from an obstacle at %s (%s)", d, describe(p),
                            house.realBlock(feet(w, p)));
                    }
                }
                return null;
            })
            .check("the trail keeps wall-clearance within the shaper's tolerance (A15 on real blocks)", () -> {
                double limit = walk.wallClearance() - TOLERANCE;
                double least = Double.MAX_VALUE;
                double[] at = null;
                for (double[] p : samples(shaped.get().trail())) {
                    double d = clearance(live, w, p);
                    if (d < least) {
                        least = d;
                        at = p;
                    }
                }
                return least >= limit ? null : String.format(Locale.ROOT, "%.2f from an obstacle at %s (%s), under %.2f",
                    least, describe(at), house.realBlock(feet(w, at)), limit);
            })
            .check("fewer of the trail's points are under wall-clearance than of today's path (guidance-v2 off)", () -> {
                double limit = walk.wallClearance() - TOLERANCE;
                double trail = shareUnder(live, w, samples(shaped.get().trail()), limit);
                double today = shareUnder(live, w, samples(today(house, terrain.get(), walk).points()), limit);
                String numbers = String.format(Locale.ROOT, "trail %.1f %%, today %.1f %% of points under %.2f; "
                    + "%d corners, %d rounded, shaped in %.2f ms", 100 * trail, 100 * today, limit,
                    shaped.get().corners(), shaped.get().rounded(), shaped.get().nanos() / 1e6);
                return trail < today ? null : numbers;
            });
    }

    /** Today's path (guidance-v2 off): the cells the trail draws without the flag. */
    static WalkPath today(NpcMovementScene house, WalkTerrain terrain, NavigationConfig.WalkConfig walk) {
        return new WalkSearch().find(request(house, terrain, walk, walk.profile(false))).path().orElseThrow();
    }

    /** The box's chunks captured with the walk capture's extractor (no gates). */
    static WalkTerrain capture(ScenarioScene scene, NavigationConfig.WalkConfig walk) {
        World world = scene.world();
        WalkSnapshotService.WorldChunks chunks = WalkSnapshotService.of(world);
        WalkChunkExtractor extractor = new WalkChunkExtractor(
            NavigationConfig.defaults().passabilityRules(ChunkSnapshotSurfaceGrid.bukkitCollidable()),
            walk.profile().climbables(), GateCells.NONE, world.getMinHeight(), world.getMaxHeight());
        List<WalkChunk> captured = new ArrayList<>();
        int x0 = scene.worldX(0) >> 4;
        int x1 = scene.worldX(scene.box().x() - 1) >> 4;
        int z0 = scene.worldZ(0) >> 4;
        int z1 = scene.worldZ(scene.box().z() - 1) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                captured.add(extractor.extract(chunks.blocks(cx, cz), cx, cz, extractor.minSection(),
                    extractor.maxSection(), s -> false, 0L));
            }
        }
        return new CapturedWalkTerrain(captured, GateCells.NONE, world.getMinHeight(), world.getMaxHeight()).terrain();
    }

    /** From the climb's start on the street to its goal in the top hall, the walk config's budget, open access. */
    static WalkRequest request(NpcMovementScene house, WalkTerrain terrain, NavigationConfig.WalkConfig walk,
                               MovementProfile profile) {
        NavPoint start = house.climbStart(0);
        NavPoint goal = house.climbGoal();
        return WalkRequest.toPoint(terrain, start.x(), start.y(), start.z(), goal.x(), goal.y() - 1, goal.z(), 1.0)
            .withProfile(profile).withBudget(walk.budget()).withAccess(CellAccess.OPEN);
    }

    /** A floor-point line sampled every {@link #SAMPLE} blocks (horizontally), its points included. */
    static List<double[]> samples(List<double[]> line) {
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            int n = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[2] - a[2]) / SAMPLE));
            for (int k = 1; k <= n; k++) {
                double t = (double) k / n;
                out.add(new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    private static int floorOf(double[] p) {
        return (int) Math.floor(p[1] + 0.5);
    }

    /** Whether the column has a standable floor within a step of {@code floorY} (live blocks). */
    private static boolean open(BukkitNavWorld live, String w, int x, int floorY, int z) {
        return live.isStandableFloor(w, x, floorY, z) || live.isStandableFloor(w, x, floorY - 1, z)
            || live.isStandableFloor(w, x, floorY + 1, z);
    }

    static boolean overFloor(BukkitNavWorld live, String w, double[] p) {
        return open(live, w, (int) Math.floor(p[0]), floorOf(p), (int) Math.floor(p[2]));
    }

    /**
     * The distance to the nearest column with no floor within a step of the point's own floor (a wall, a post, a
     * drop), at most 2: the clearance map's rule, judged from the standable floor of the point's column nearest its
     * height (on a stair the drawn height lies between two steps).
     */
    static double clearance(BukkitNavWorld live, String w, double[] p) {
        int cx = (int) Math.floor(p[0]);
        int cz = (int) Math.floor(p[2]);
        int floor = floorOf(p);
        int own = floor;
        double ownD = Double.MAX_VALUE;
        for (int f = floor - 1; f <= floor + 1; f++) {
            if (live.isStandableFloor(w, cx, f, cz) && Math.abs(f - p[1]) < ownD) {
                ownD = Math.abs(f - p[1]);
                own = f;
            }
        }
        floor = own;
        double best = 2;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double ddx = Math.max(Math.max(x - p[0], 0), p[0] - (x + 1));
                double ddz = Math.max(Math.max(z - p[2], 0), p[2] - (z + 1));
                double d = Math.hypot(ddx, ddz);
                if (d < best && !open(live, w, x, floor, z)) {
                    best = d;
                }
            }
        }
        return best;
    }

    private static double shareUnder(BukkitNavWorld live, String w, List<double[]> points, double limit) {
        int under = 0;
        for (double[] p : points) {
            if (clearance(live, w, p) < limit) {
                under++;
            }
        }
        return points.isEmpty() ? 0 : (double) under / points.size();
    }

    private static NavPoint feet(String w, double[] p) {
        return new NavPoint(w, p[0], floorOf(p) + 1, p[2]);
    }

    static String describe(double[] p) {
        return String.format(Locale.ROOT, "(%.2f, %.1f, %.2f)", p[0], p[1], p[2]);
    }
}
