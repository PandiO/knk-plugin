package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * The pure half of {@code RoadDirtyTracker} (DESIGN §5.9): which block changes matter, and the
 * batch of tiles waiting for the next {@code markDirty} flush. Thread-safe - the WorldEdit hook can
 * mark from any thread under FAWE.
 *
 * <p>A change matters when the block's material is a floor material of any road profile (a road block
 * placed or broken), or the block is a road span of the current network or one of the two headroom
 * blocks above it (a wall built across a road). Everything else (a tree, a house wall) is ignored.
 */
public final class DirtyTiles {
    /** Headroom blocks above a road floor that, when filled, block the road (DESIGN §5.2). */
    public static final int HEADROOM = 2;

    private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
    private volatile Predicate<String> roadMaterial = m -> false;
    private volatile RoadCells cells = RoadCells.EMPTY;

    /** The road floor cells of a snapshot, packed with {@link BlockKey}. */
    public static final class RoadCells {
        public static final RoadCells EMPTY = new RoadCells(Set.of());
        private final Set<Long> floors;

        RoadCells(Set<Long> floors) {
            this.floors = floors;
        }

        /** Every geometry point of every edge, plus the cells between consecutive points. */
        public static RoadCells of(RoadNetworkSnapshot snapshot) {
            if (snapshot == null) {
                return EMPTY;
            }
            Set<Long> floors = new HashSet<>();
            for (RoadEdge edge : snapshot.edges()) {
                List<int[]> geometry = edge.geometry();
                for (int i = 0; i < geometry.size(); i++) {
                    int[] p = geometry.get(i);
                    floors.add(BlockKey.pack(p[0], p[1], p[2]));
                    if (i + 1 < geometry.size()) {
                        int[] q = geometry.get(i + 1);
                        int steps = Math.max(Math.abs(q[0] - p[0]), Math.max(Math.abs(q[1] - p[1]), Math.abs(q[2] - p[2])));
                        for (int s = 1; s < steps; s++) {
                            floors.add(BlockKey.pack(
                                p[0] + Math.round((q[0] - p[0]) * (float) s / steps),
                                p[1] + Math.round((q[1] - p[1]) * (float) s / steps),
                                p[2] + Math.round((q[2] - p[2]) * (float) s / steps)));
                        }
                    }
                }
            }
            return new RoadCells(floors);
        }

        public int size() {
            return floors.size();
        }

        public boolean isFloor(int x, int y, int z) {
            return floors.contains(BlockKey.pack(x, y, z));
        }

        /** The block is a road floor or within {@link #HEADROOM} blocks above one. */
        public boolean touchesRoad(int x, int y, int z) {
            for (int dy = 0; dy <= HEADROOM; dy++) {
                if (floors.contains(BlockKey.pack(x, y - dy, z))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The material names (upper case) of every enabled profile's floor materials. */
    public void setRoadMaterials(Set<String> materialNames) {
        Set<String> upper = new HashSet<>();
        for (String name : materialNames) {
            upper.add(name.toUpperCase(Locale.ROOT));
        }
        this.roadMaterial = upper::contains;
    }

    /** The current network's road cells, replaced whenever the cache swaps a snapshot. */
    public void setRoadCells(RoadCells cells) {
        this.cells = cells == null ? RoadCells.EMPTY : cells;
    }

    public RoadCells roadCells() {
        return cells;
    }

    /** Whether a block change at {@code (x, y, z)} of material {@code materialName} can change the road network. */
    public boolean matters(String materialName, int x, int y, int z) {
        if (materialName != null && roadMaterial.test(materialName.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return cells.touchesRoad(x, y, z);
    }

    /** Marks the tile of a block that matters; returns true when it was (newly) marked. */
    public boolean markIfMatters(String world, String materialName, int x, int y, int z) {
        if (!matters(materialName, x, y, z)) {
            return false;
        }
        return mark(TileKey.of(world, x, z));
    }

    /** Marks a tile unconditionally (WorldEdit changes, admin requests). */
    public boolean mark(TileKey key) {
        return pending.add(key);
    }

    public boolean mark(String world, int blockX, int blockZ) {
        return mark(TileKey.of(world, blockX, blockZ));
    }

    public int pendingCount() {
        return pending.size();
    }

    public Set<TileKey> pending() {
        return Set.copyOf(pending);
    }

    /** Takes every pending tile out of the batch (the flush; failures are re-marked by the caller). */
    public List<TileKey> drain() {
        List<TileKey> batch = new ArrayList<>(pending);
        pending.removeAll(batch);
        return batch;
    }
}
