package net.knightsandkings.knk.paper.navigation.walk;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkRecomputeHint;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.roads.GateCellsIndex;

/**
 * The server side of {@link NavigationService.WalkPreparer} (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §3, §6, §8): per walk leg, on the main thread, the world's gate cells now, the {@link WalkBox}
 * around the leg, the capture ({@link WalkSnapshotService}, loaded chunks only) and the player's
 * gate/door/region access ({@link WalkAccessFactory#prepare}, with the destination's regions for the "no way out"
 * rule, KNG-121). The supplier it completes with runs on
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
    private final int maxChunks;
    private final MovementProfile profile;
    private final WalkBudget budget;

    /**
     * @param gatesOf  the gate-door cells of a world now (server: {@link GateCellsIndex#of})
     * @param chunksOf a world's chunks (server: {@link WalkSnapshotService#of})
     */
    public WalkLegPreparer(WalkSnapshotService snapshots, WalkAccessFactory access, NavigationConfig.WalkConfig config,
                           Function<String, GateCells> gatesOf, Function<World, WalkSnapshotService.WorldChunks> chunksOf) {
        this(snapshots, access, config, false, gatesOf, chunksOf);
    }

    /**
     * As above; with {@code guidanceV2} ({@code navigation.guidance-v2}, phase 6.1) the requests carry the profile with
     * the clearance cost ({@link NavigationConfig.WalkConfig#profile(boolean)}); off, today's profile exactly.
     */
    public WalkLegPreparer(WalkSnapshotService snapshots, WalkAccessFactory access, NavigationConfig.WalkConfig config,
                           boolean guidanceV2, Function<String, GateCells> gatesOf,
                           Function<World, WalkSnapshotService.WorldChunks> chunksOf) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.access = Objects.requireNonNull(access, "access");
        this.gatesOf = Objects.requireNonNull(gatesOf, "gatesOf");
        this.chunksOf = Objects.requireNonNull(chunksOf, "chunksOf");
        Objects.requireNonNull(config, "config");
        this.captureMargin = config.captureMargin();
        this.maxChunks = config.maxChunksPerRequest();
        this.profile = config.profile(guidanceV2);
        this.budget = config.budget();
    }

    /** The server's preparer: the gate cache's door cells and Bukkit chunk snapshots. */
    public static WalkLegPreparer server(WalkSnapshotService snapshots, WalkAccessFactory access,
                                         NavigationConfig.WalkConfig config, GateManager gateManager) {
        return server(snapshots, access, config, false, gateManager);
    }

    /** The server's preparer with {@code navigation.guidance-v2} on or off (phase 6.1). */
    public static WalkLegPreparer server(WalkSnapshotService snapshots, WalkAccessFactory access,
                                         NavigationConfig.WalkConfig config, boolean guidanceV2, GateManager gateManager) {
        return new WalkLegPreparer(snapshots, access, config, guidanceV2, world -> GateCellsIndex.of(gateManager, world),
            WalkSnapshotService::of);
    }

    @Override
    public CompletableFuture<Supplier<WalkRequest>> prepare(Player player, double[] feet, double[] target, WalkGoal goal,
                                                            Set<String> destinationRegions) {
        return prepare(player, feet, target, goal, destinationRegions, null);
    }

    /**
     * With a {@link WalkRecomputeHint} (PandiO/knk-workspace#771 item 1): the capture box also holds the rest of the
     * leg's found path ({@link #HINT_MARGIN} around it), unless that takes it over {@code max-chunks-per-request} (then
     * the box of the leg alone, as before), and the length cap allows at least the hint's length. Without one, exactly
     * as before.
     */
    @Override
    public CompletableFuture<Supplier<WalkRequest>> prepare(Player player, double[] feet, double[] target, WalkGoal goal,
                                                            Set<String> destinationRegions, WalkRecomputeHint hint) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(goal, "goal");
        double[] from = feet.clone();
        double[] to = target.clone();
        World world = player.getWorld();
        GateCells gates = gatesOf.apply(world.getName());
        WalkBox box = boxFor(WalkBox.around(world.getName(), from[0], from[1], from[2], to[0], to[1], to[2],
            captureMargin, world.getMinHeight(), world.getMaxHeight()), hint, world.getMinHeight(), world.getMaxHeight());
        CompletableFuture<WalkSnapshotService.WalkCapture> capture = snapshots.capture(chunksOf.apply(world), gates, box);
        CompletableFuture<Supplier<WalkRequest>> prepared = capture.thenApply(captured -> {
            if (!captured.ready()) {
                return null;
            }
            WalkAccessFactory.WalkAccess walkAccess = access.prepare(player, world, gates, captured, profile.headroom(),
                destinationRegions);
            WalkTerrain terrain = captured.terrain().terrain();
            Supplier<WalkRequest> request = () -> {
                WalkRequest plain = new WalkRequest(terrain, walkAccess.resolve(), profile, from[0], from[1], from[2],
                    to[0], to[1], to[2], goal, budget);
                return hint == null ? plain
                    : plain.withBudget(hint.widen(budget, plain.straightDistance(), plain.heightDifference()));
            };
            return new NavigationService.PreparedWalk(request, captured.stats());
        });
        prepared.whenComplete((ignored, error) -> {
            if (prepared.isCancelled()) {
                capture.cancel(false);
            }
        });
        return prepared;
    }

    /** Blocks around the hint's path kept in the capture: the headroom above a floor and a step aside. */
    public static final int HINT_MARGIN = 3;

    /** The leg's box, grown to hold the hint's path when that stays within {@code max-chunks-per-request}. */
    WalkBox boxFor(WalkBox legBox, WalkRecomputeHint hint, int worldMinY, int worldMaxY) {
        if (hint == null) {
            return legBox;
        }
        WalkBox grown = legBox.including(hint.bounds(), HINT_MARGIN, worldMinY, worldMaxY);
        return grown.chunkCount() <= maxChunks ? grown : legBox;
    }

    /**
     * PandiO/knk-workspace#360: what a local leg of a mover with its own access subject (an NPC) needs for the
     * engine's path search - the box and the subject's cell access in it.
     *
     * @param access the subject's access, built on the walk executor (domain lookups may block); READY only
     */
    public record LegCapture(WalkSnapshotService.Status status, WalkBox box, Supplier<CellAccess> access) {
    }

    /**
     * Main thread: captures the box around a leg from {@code feet} to {@code target} (a floor point) and decides the
     * subject's gate, door and region access in it, judged from the subject's own regions. Loaded chunks only: an
     * unloaded chunk or a box too large completes with that status and no access. Completes on the main thread.
     */
    public CompletableFuture<LegCapture> capture(AccessSubject subject, World world, double[] feet, double[] target) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(world, "world");
        GateCells gates = gatesOf.apply(world.getName());
        WalkBox box = WalkBox.around(world.getName(), feet[0], feet[1], feet[2], target[0], target[1], target[2],
            captureMargin, world.getMinHeight(), world.getMaxHeight());
        return snapshots.capture(chunksOf.apply(world), gates, box).thenApply(captured -> {
            if (!captured.ready()) {
                return new LegCapture(captured.status(), box, null);
            }
            WalkAccessFactory.WalkAccess walkAccess = access.prepare(subject, world, gates, captured,
                profile.headroom(), null);
            return new LegCapture(captured.status(), box, walkAccess::resolve);
        });
    }

    @Override
    public String describe() {
        WalkSnapshotService.Stats stats = snapshots.stats();
        return String.format(java.util.Locale.ROOT, "capture: %d chunks (%.0f µs each), %d cache hits, %d cached (%d KB), %d waiting",
            stats.chunksCaptured(), stats.meanCaptureMicros(), stats.cacheHits(), stats.cachedChunks(),
            stats.cachedBytes() / 1024, stats.pendingRequests());
    }
}
