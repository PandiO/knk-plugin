package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.siege.SiegeFloor;
import net.knightsandkings.knk.core.util.BlockKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Grows the road mask from seeds (DESIGN §5.1, §5.4):
 *
 * <ol>
 *   <li>every seed is snapped to a road span — its own column first (feet or floor height, then
 *       {@code SiegeFloor.floorY}, reuse map R28), else the nearest span within
 *       {@link BuildParameters#seedSnapRadius()} in x/z and ±{@value #SEED_SNAP_DY} in y; a seed
 *       without a span is a warning;</li>
 *   <li>a BFS over {@link SpanGrid} links from the seed spans, clipped to the region (tile plus
 *       margin) and capped at {@link BuildParameters#maxCellsPerTile()} spans — hitting the cap is a
 *       warning at the span where it stopped;</li>
 *   <li>ambiguous spans (DESIGN §5.1) are kept only within {@link BuildParameters#ambiguousReach()}
 *       spans of an unambiguous span (a second, multi-source BFS distance field), and what is no
 *       longer connected to a seed after that is dropped (a third BFS).</li>
 * </ol>
 *
 * Pure: the world is only read through the span grid.
 */
public final class MaskBuilder {

    /** A seed position: a Domain Location, a survey breadcrumb, an admin seed or a neighbour tile's boundary node. */
    public record Seed(int x, int y, int z) {
    }

    /** The x/z rectangle (inclusive) the mask may occupy: the tile plus its margin. */
    public record Region(int minX, int minZ, int maxX, int maxZ) {
        public Region {
            if (maxX < minX || maxZ < minZ) {
                throw new IllegalArgumentException("empty region " + minX + ".." + maxX + " × " + minZ + ".." + maxZ);
            }
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** The region grown by {@code margin} on every side. */
        public Region grow(int margin) {
            return new Region(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        /** The tile {@code (tileX, tileZ)} of side {@code size}. */
        public static Region tile(int tileX, int tileZ, int size) {
            return new Region(tileX * size, tileZ * size, tileX * size + size - 1, tileZ * size + size - 1);
        }
    }

    /**
     * The built mask plus what happened on the way.
     *
     * @param mask           the road mask
     * @param seedSpans      mask indices the seeds landed on (deduplicated, in seed order; seeds that
     *                       snapped to a span later filtered out are omitted)
     * @param reachedBeforeFilter spans the first BFS reached before the ambiguity filter
     * @param cappedAtCellLimit   whether the BFS stopped at the cell cap
     * @param warnings       unmatched seeds, cell cap
     */
    public record Result(RoadMask mask, List<Integer> seedSpans, int reachedBeforeFilter,
                         boolean cappedAtCellLimit, List<BuildWarning> warnings) {
    }

    /** How far above or below a seed's y a span is looked for when snapping. */
    public static final int SEED_SNAP_DY = 4;

    public static final String WARN_SEED_UNMATCHED = "Seed has no road span within reach";
    public static final String WARN_CELL_CAP = "Cell cap reached; the mask is incomplete";

    private final SpanGrid grid;
    private final BuildParameters params;

    public MaskBuilder(SpanGrid grid, BuildParameters params) {
        this.grid = Objects.requireNonNull(grid, "grid");
        this.params = Objects.requireNonNull(params, "params");
    }

    /** Build the mask reachable from the seeds inside the region. */
    public Result build(Collection<Seed> seeds, Region region) {
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(region, "region");
        List<BuildWarning> warnings = new ArrayList<>();

        // 1. Seeds → spans.
        List<Long> seedKeys = new ArrayList<>();
        Set<Long> seenSeeds = new HashSet<>();
        for (Seed seed : seeds) {
            OptionalLong span = snapSeed(seed, region);
            if (span.isEmpty()) {
                warnings.add(new BuildWarning(WARN_SEED_UNMATCHED, seed.x(), seed.y(), seed.z()));
            } else if (seenSeeds.add(span.getAsLong())) {
                seedKeys.add(span.getAsLong());
            }
        }

        // 2. Reach.
        Set<Long> reached = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        boolean capped = false;
        for (long key : seedKeys) {
            if (reached.add(key)) {
                queue.add(key);
            }
        }
        long capHit = 0;
        outer:
        while (!queue.isEmpty()) {
            long key = queue.poll();
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int dy = grid.neighbourDy(key, d);
                if (dy == SpanGrid.NO_LINK) {
                    continue;
                }
                long nb = BlockKey.neighbour(key, SpanGrid.DX[d], dy, SpanGrid.DZ[d]);
                if (!region.contains(BlockKey.x(nb), BlockKey.z(nb))) {
                    continue;
                }
                if (reached.add(nb)) {
                    if (reached.size() > params.maxCellsPerTile()) {
                        reached.remove(nb);
                        capped = true;
                        capHit = nb;
                        break outer;
                    }
                    queue.add(nb);
                }
            }
        }
        if (capped) {
            warnings.add(new BuildWarning(WARN_CELL_CAP, BlockKey.x(capHit), BlockKey.y(capHit), BlockKey.z(capHit)));
        }
        int reachedCount = reached.size();

        long[] keys = new long[reached.size()];
        int k = 0;
        for (long key : reached) {
            keys[k++] = key;
        }
        RoadMask all = RoadMask.of(grid, keys);

        // 3. Ambiguity filter, then re-reach from the seeds.
        RoadMask mask = filterAmbiguous(all, seedKeys);

        List<Integer> seedSpans = new ArrayList<>();
        for (long key : seedKeys) {
            int i = mask.indexOf(key);
            if (i != RoadMask.NONE) {
                seedSpans.add(i);
            }
        }
        return new Result(mask, Collections.unmodifiableList(seedSpans), reachedCount, capped,
            Collections.unmodifiableList(warnings));
    }

    /**
     * Keep unambiguous spans and ambiguous spans within {@code ambiguousReach} of one (BFS hops over
     * the mask), then keep only what a seed still reaches.
     */
    private RoadMask filterAmbiguous(RoadMask all, List<Long> seedKeys) {
        int n = all.size();
        if (n == 0) {
            return all;
        }
        int[] distance = new int[n];
        Arrays.fill(distance, -1);
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        boolean anyAmbiguous = false;
        for (int i = 0; i < n; i++) {
            if (!grid.isAmbiguous(all.key(i))) {
                distance[i] = 0;
                queue[tail++] = i;
            } else {
                anyAmbiguous = true;
            }
        }
        if (!anyAmbiguous) {
            return all;
        }
        int reach = params.ambiguousReach();
        while (head < tail) {
            int i = queue[head++];
            if (distance[i] >= reach) {
                continue;
            }
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = all.neighbour(i, d);
                if (nb != RoadMask.NONE && distance[nb] == -1) {
                    distance[nb] = distance[i] + 1;
                    queue[tail++] = nb;
                }
            }
        }
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            keep[i] = distance[i] != -1;
        }
        RoadMask kept = all.subset(keep);

        // Re-reach from the seeds over what is left.
        boolean[] connected = new boolean[kept.size()];
        int[] queue2 = new int[kept.size()];
        head = 0;
        tail = 0;
        for (long key : seedKeys) {
            int i = kept.indexOf(key);
            if (i != RoadMask.NONE && !connected[i]) {
                connected[i] = true;
                queue2[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue2[head++];
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = kept.neighbour(i, d);
                if (nb != RoadMask.NONE && !connected[nb]) {
                    connected[nb] = true;
                    queue2[tail++] = nb;
                }
            }
        }
        return kept.subset(connected);
    }

    /**
     * The span a seed stands for: its column at the seed's y (a floor) or y - 1 (feet), then the
     * floor {@code SiegeFloor.floorY} finds below/above, then the nearest span in a growing x/z ring
     * within {@code seedSnapRadius} and ±{@value #SEED_SNAP_DY} blocks of height. Only spans inside the
     * region count.
     */
    OptionalLong snapSeed(Seed seed, Region region) {
        int x = seed.x();
        int y = seed.y();
        int z = seed.z();
        if (region.contains(x, z)) {
            if (grid.isSpan(x, y, z)) {
                return OptionalLong.of(BlockKey.pack(x, y, z));
            }
            if (grid.isSpan(x, y - 1, z)) {
                return OptionalLong.of(BlockKey.pack(x, y - 1, z));
            }
            SurfaceGrid surface = grid.surface();
            OptionalDouble floorTop = SiegeFloor.floorY(y + 0.5, by -> surface.isSolid(x, by, z), by -> by + 1.0);
            if (floorTop.isPresent()) {
                int floorY = (int) Math.floor(floorTop.getAsDouble()) - 1;
                if (grid.isSpan(x, floorY, z)) {
                    return OptionalLong.of(BlockKey.pack(x, floorY, z));
                }
            }
        }
        for (int r = 0; r <= params.seedSnapRadius(); r++) {
            long best = 0;
            int bestDy = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = x + dx;
                    int cz = z + dz;
                    if (!region.contains(cx, cz)) {
                        continue;
                    }
                    for (int dy = 0; dy <= SEED_SNAP_DY; dy++) {
                        if (dy >= bestDy) {
                            break;
                        }
                        if (grid.isSpan(cx, y - dy, cz)) {
                            best = BlockKey.pack(cx, y - dy, cz);
                            bestDy = dy;
                            break;
                        }
                        if (dy > 0 && grid.isSpan(cx, y + dy, cz)) {
                            best = BlockKey.pack(cx, y + dy, cz);
                            bestDy = dy;
                            break;
                        }
                    }
                }
            }
            if (bestDy != Integer.MAX_VALUE) {
                return OptionalLong.of(best);
            }
        }
        return OptionalLong.empty();
    }
}
