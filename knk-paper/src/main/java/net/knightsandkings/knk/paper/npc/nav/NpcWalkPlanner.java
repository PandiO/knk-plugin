package net.knightsandkings.knk.paper.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.OffRoadWalks;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkPath;
import net.knightsandkings.knk.core.roads.walk.WalkPathfinder;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.walk.WalkAccessFactory;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;
import net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService;

/**
 * {@link OffRoadWalks} on player navigation's walk navigator (PandiO/knk-workspace#700, link C1): it calls the walk
 * capture ({@link WalkSnapshotService}, loaded chunks only, main thread), the subject's gate, door and region access
 * ({@link WalkAccessFactory#prepare(AccessSubject, World, GateCells, WalkSnapshotService.WalkCapture, int, Set)}) and
 * today's walk search ({@link WalkPathfinder}, the walk executor) with the walk settings' budget, and changes none of
 * them. The box covers the NPC's feet and every target plus {@code navigation.walk.capture-margin}.
 * <p>
 * The movement profile is the walk settings' with three NPC changes (agent decision C1): doors only when the NPC's
 * access profile opens doors, no ladders (a Citizens NPC following waypoints does not climb), no wading (an NPC in water
 * drowns, #490).
 */
public final class NpcWalkPlanner implements OffRoadWalks {

    /** The walk navigator's parts the planner calls; built once by the plugin (or a scenario). */
    public record Sources(WalkSnapshotService snapshots, Function<String, GateCells> gatesOf,
                          Function<World, WalkSnapshotService.WorldChunks> chunksOf, NavigationConfig.WalkConfig config,
                          WalkPathfinder pathfinder, Executor searches, Executor mainThread,
                          Function<String, World> worlds) {
        public Sources {
            Objects.requireNonNull(snapshots, "snapshots");
            Objects.requireNonNull(gatesOf, "gatesOf");
            Objects.requireNonNull(chunksOf, "chunksOf");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(pathfinder, "pathfinder");
            Objects.requireNonNull(searches, "searches");
            Objects.requireNonNull(mainThread, "mainThread");
            Objects.requireNonNull(worlds, "worlds");
        }
    }

    /**
     * The subject's cell access in a captured box (main thread); the supplier runs on the walk executor (domain
     * lookups may block).
     */
    @FunctionalInterface
    public interface AccessFor {
        Supplier<CellAccess> prepare(AccessSubject subject, World world, GateCells gates,
                                     WalkSnapshotService.WalkCapture capture, int headroom);

        /** The live access: gates, doors and regions as the subject may use them. */
        static AccessFor of(WalkAccessFactory factory) {
            Objects.requireNonNull(factory, "factory");
            return (subject, world, gates, capture, headroom) ->
                factory.prepare(subject, world, gates, capture, headroom, null)::resolve;
        }

        /** Everything open (scenarios with a fake road and no rules). */
        AccessFor OPEN = (subject, world, gates, capture, headroom) -> () -> CellAccess.OPEN;
    }

    private final Sources sources;
    private final AccessFor access;
    private final Logger logger;

    public NpcWalkPlanner(Sources sources, AccessFor access, Logger logger) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.access = Objects.requireNonNull(access, "access");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The walk settings' profile for an NPC: doors as its access profile says, no ladders, no wading. */
    static MovementProfile npcProfile(MovementProfile walk, boolean opensDoors) {
        return new MovementProfile(walk.headroom(), walk.maxDrop(), walk.dropPenalty(), walk.jumpCost(), opensDoors,
            walk.doorCost(), false, walk.waterFactor(), Set.of(), walk.climbCost(), walk.wallCost());
    }

    /** The box over the feet and every target, plus the margin, inside the world's heights. */
    static WalkBox boxOver(String world, NavPoint feet, List<NavPoint> targets, int margin, int minY, int maxY) {
        int x0 = (int) Math.floor(feet.x());
        int x1 = x0;
        int y0 = (int) Math.floor(feet.y()) - 1;
        int y1 = y0;
        int z0 = (int) Math.floor(feet.z());
        int z1 = z0;
        for (NavPoint t : targets) {
            x0 = Math.min(x0, t.blockX());
            x1 = Math.max(x1, t.blockX());
            y0 = Math.min(y0, t.blockY());
            y1 = Math.max(y1, t.blockY());
            z0 = Math.min(z0, t.blockZ());
            z1 = Math.max(z1, t.blockZ());
        }
        int lo = Math.max(minY, y0 - margin);
        int hi = Math.min(maxY - 1, y1 + 1 + margin);
        if (lo > hi) {
            lo = hi = Math.max(minY, Math.min(maxY - 1, y1));
        }
        return new WalkBox(world, x0 - margin, lo, z0 - margin, x1 + margin, hi, z1 + margin);
    }

    /** Arrived within {@code arrive} blocks (3D, floor positions) of any target. */
    static WalkGoal anyOf(List<NavPoint> targets, double arrive) {
        double max = arrive * arrive;
        double[][] t = new double[targets.size()][];
        for (int i = 0; i < t.length; i++) {
            NavPoint p = targets.get(i);
            t[i] = new double[] {p.x(), p.y(), p.z()};
        }
        return (x, floorY, z) -> reachedIndex(t, x, floorY, z, max) >= 0;
    }

    private static int reachedIndex(double[][] t, double x, double floorY, double z, double max) {
        for (int i = 0; i < t.length; i++) {
            double dx = x - t[i][0];
            double dy = floorY - t[i][1];
            double dz = z - t[i][2];
            if (dx * dx + dy * dy + dz * dz <= max) {
                return i;
            }
        }
        return -1;
    }

    /** The walked cells as floor points (block centres, floor y). */
    static List<NavPoint> floorPoints(String world, WalkPath path) {
        List<NavPoint> out = new ArrayList<>(path.size());
        for (double[] p : path.points()) {
            out.add(new NavPoint(world, p[0], p[1], p[2]));
        }
        return out;
    }

    @Override
    public CompletableFuture<Walk> plan(AccessSubject subject, NavPoint feet, List<NavPoint> targets, double arrive) {
        Objects.requireNonNull(subject, "subject");
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(Walk.none(Status.FAILED, "no target"));
        }
        World world = sources.worlds().apply(feet.world());
        if (world == null) {
            return CompletableFuture.completedFuture(Walk.none(Status.FAILED, "no world " + feet.world()));
        }
        NavigationConfig.WalkConfig config = sources.config();
        GateCells gates = sources.gatesOf().apply(world.getName());
        WalkBox box = boxOver(world.getName(), feet, targets, config.captureMargin(), world.getMinHeight(),
            world.getMaxHeight());
        boolean opensDoors = !(subject instanceof NpcAccessSubject npc) || npc.profile().openDoors();
        MovementProfile profile = npcProfile(config.profile(), opensDoors);
        WalkBudget budget = config.budget();
        List<NavPoint> aims = List.copyOf(targets);
        NavPoint aim = aims.get(0);
        CompletableFuture<Walk> out = new CompletableFuture<>();
        sources.snapshots().capture(sources.chunksOf().apply(world), gates, box).whenComplete((captured, error) -> {
            if (error != null || captured == null) {
                logger.log(Level.WARNING, "[NPC] off-road walk capture failed for " + subject.name(), error);
                out.complete(Walk.none(Status.FAILED, "capture error"));
                return;
            }
            switch (captured.status()) {
                case UNLOADED -> out.complete(Walk.none(Status.UNLOADED, "unloaded chunk in the walk's box"));
                case TOO_LARGE -> out.complete(Walk.none(Status.TOO_LARGE, "box of " + box.chunkCount() + " chunks"));
                default -> {
                    Supplier<CellAccess> cells;
                    try {
                        cells = access.prepare(subject, world, gates, captured, profile.headroom());
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "[NPC] off-road walk access failed for " + subject.name(), e);
                        out.complete(Walk.none(Status.FAILED, "access error"));
                        return;
                    }
                    WalkTerrain terrain = captured.terrain().terrain();
                    CompletableFuture.supplyAsync(() -> search(terrain, cells.get(), profile, budget, feet, aims, aim,
                        arrive), sources.searches()).whenComplete((walk, searchError) -> sources.mainThread().execute(() -> {
                            if (searchError != null || walk == null) {
                                logger.log(Level.WARNING, "[NPC] off-road walk search failed for " + subject.name(),
                                    searchError);
                                out.complete(Walk.none(Status.FAILED, "search error"));
                            } else {
                                out.complete(walk);
                            }
                        }));
                }
            }
        });
        return out;
    }

    /** The search itself (walk executor): today's walk search, unchanged, with a goal over every target. */
    static Walk search(WalkTerrain terrain, CellAccess cells, MovementProfile profile, WalkBudget budget,
                       NavPoint feet, List<NavPoint> targets, NavPoint aim, double arrive, WalkPathfinder pathfinder) {
        WalkRequest request = new WalkRequest(terrain, cells, profile, feet.x(), feet.y(), feet.z(), aim.x(), aim.y(),
            aim.z(), anyOf(targets, arrive), budget);
        WalkResult result = pathfinder.find(request);
        double cap = budget.lengthCap(request.straightDistance(), request.heightDifference());
        String numbers = String.format(Locale.ROOT, "%d expansions, cap %.0f blocks", result.expansions(), cap);
        if (!result.isFound()) {
            return Walk.none(Status.NO_PATH, result.status() + ": " + result.reason() + ", " + numbers);
        }
        WalkPath path = result.path().orElseThrow();
        List<NavPoint> points = floorPoints(feet.world(), path);
        NavPoint end = points.get(points.size() - 1);
        double[][] t = new double[targets.size()][];
        for (int i = 0; i < t.length; i++) {
            t[i] = new double[] {targets.get(i).x(), targets.get(i).y(), targets.get(i).z()};
        }
        int reached = Math.max(0, reachedIndex(t, end.x(), end.y(), end.z(), arrive * arrive));
        return Walk.found(points, reached, String.format(Locale.ROOT, "%.0f blocks walked, %s", path.length(), numbers));
    }

    private Walk search(WalkTerrain terrain, CellAccess cells, MovementProfile profile, WalkBudget budget,
                        NavPoint feet, List<NavPoint> targets, NavPoint aim, double arrive) {
        return search(terrain, cells, profile, budget, feet, targets, aim, arrive, sources.pathfinder());
    }
}
