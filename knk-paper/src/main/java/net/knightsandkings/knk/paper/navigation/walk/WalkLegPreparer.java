package net.knightsandkings.knk.paper.navigation.walk;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.roads.GateCellsIndex;

/**
 * The server side of {@link NavigationService.WalkPreparer} (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §3, §6, §8): per walk leg, on the main thread, the world's gate cells now, the {@link WalkBox}
 * around the leg, the capture ({@link WalkSnapshotService}, loaded chunks only) and the player's
 * gate/door/region access ({@link WalkAccessFactory#prepare}). The supplier it completes with runs on
 * the walk executor: it resolves the domain part of the access (decision L3-5) and builds the
 * {@link WalkRequest} with {@code navigation.walk}'s profile and budget. A capture that is not READY
 * (an unloaded chunk, a box too large) completes with null: the leg keeps its straight line.
 * Cancelling the returned future cancels the capture.
 */
public final class WalkLegPreparer implements NavigationService.WalkPreparer {

    private final WalkSnapshotService snapshots;
    private final WalkAccessFactory access;
    private final Function<String, GateCells> gatesOf;
    private final Function<World, WalkSnapshotService.WorldChunks> chunksOf;
    private final int captureMargin;
    private final MovementProfile profile;
    private final WalkBudget budget;

    /**
     * @param gatesOf  the gate-door cells of a world now (server: {@link GateCellsIndex#of})
     * @param chunksOf a world's chunks (server: {@link WalkSnapshotService#of})
     */
    public WalkLegPreparer(WalkSnapshotService snapshots, WalkAccessFactory access, NavigationConfig.WalkConfig config,
                           Function<String, GateCells> gatesOf, Function<World, WalkSnapshotService.WorldChunks> chunksOf) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.access = Objects.requireNonNull(access, "access");
        this.gatesOf = Objects.requireNonNull(gatesOf, "gatesOf");
        this.chunksOf = Objects.requireNonNull(chunksOf, "chunksOf");
        Objects.requireNonNull(config, "config");
        this.captureMargin = config.captureMargin();
        this.profile = config.profile();
        this.budget = config.budget();
    }

    /** The server's preparer: the gate cache's door cells and Bukkit chunk snapshots. */
    public static WalkLegPreparer server(WalkSnapshotService snapshots, WalkAccessFactory access,
                                         NavigationConfig.WalkConfig config, GateManager gateManager) {
        return new WalkLegPreparer(snapshots, access, config, world -> GateCellsIndex.of(gateManager, world),
            WalkSnapshotService::of);
    }

    @Override
    public CompletableFuture<Supplier<WalkRequest>> prepare(Player player, double[] feet, double[] target, WalkGoal goal) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(goal, "goal");
        double[] from = feet.clone();
        double[] to = target.clone();
        World world = player.getWorld();
        GateCells gates = gatesOf.apply(world.getName());
        WalkBox box = WalkBox.around(world.getName(), from[0], from[1], from[2], to[0], to[1], to[2],
            captureMargin, world.getMinHeight(), world.getMaxHeight());
        CompletableFuture<WalkSnapshotService.WalkCapture> capture = snapshots.capture(chunksOf.apply(world), gates, box);
        CompletableFuture<Supplier<WalkRequest>> prepared = capture.thenApply(captured -> {
            if (!captured.ready()) {
                return null;
            }
            WalkAccessFactory.WalkAccess walkAccess = access.prepare(player, world, gates, captured, profile.headroom());
            WalkTerrain terrain = captured.terrain().terrain();
            return () -> new WalkRequest(terrain, walkAccess.resolve(), profile, from[0], from[1], from[2],
                to[0], to[1], to[2], goal, budget);
        });
        prepared.whenComplete((ignored, error) -> {
            if (prepared.isCancelled()) {
                capture.cancel(false);
            }
        });
        return prepared;
    }

    @Override
    public String describe() {
        WalkSnapshotService.Stats stats = snapshots.stats();
        return String.format(java.util.Locale.ROOT, "capture: %d chunks (%.0f µs each), %d cache hits, %d cached (%d KB), %d waiting",
            stats.chunksCaptured(), stats.meanCaptureMicros(), stats.cacheHits(), stats.cachedChunks(),
            stats.cachedBytes() / 1024, stats.pendingRequests());
    }
}
