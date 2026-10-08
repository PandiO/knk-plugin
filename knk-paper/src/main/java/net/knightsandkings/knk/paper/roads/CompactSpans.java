package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.util.BlockKey;

/**
 * The compact per-chunk extraction the tile build keeps instead of chunk snapshots (DESIGN §9; plan
 * Phase 3 "ChunkSnapshotSurfaceGrid"): per chunk a sorted {@code long[]} of candidate-span keys
 * ({@link BlockKey}) with, per span, an interned material id and a flags byte. Pure, Bukkit-free;
 * {@code ChunkSnapshotSurfaceGrid} fills it on the main thread and answers the builder from it off-thread.
 *
 * <p>Flags: {@link #FLAG_STAIR_OR_SLAB} (the floor is a stair or slab - the step-up rule of DESIGN §5.2),
 * {@link #FLAG_THIRD_PASSABLE} (the third block above the floor is passable - the head room a step up from
 * this span needs), {@link #FLAG_GATE} (the span or its headroom is a gate-door cell).
 */
public final class CompactSpans {
    public static final int FLAG_STAIR_OR_SLAB = 1;
    public static final int FLAG_THIRD_PASSABLE = 2;
    public static final int FLAG_GATE = 4;

    /** One chunk's spans, keys sorted for binary search. */
    public static final class ChunkSpans {
        final long[] keys;
        final short[] materials;
        final byte[] flags;

        ChunkSpans(long[] keys, short[] materials, byte[] flags) {
            this.keys = keys;
            this.materials = materials;
            this.flags = flags;
        }

        public int size() {
            return keys.length;
        }

        int indexOf(long key) {
            return Arrays.binarySearch(keys, key);
        }
    }

    /** Collects one chunk's spans in any order; {@link #build()} sorts them. */
    public static final class ChunkBuilder {
        private final CompactSpans owner;
        private final int chunkX;
        private final int chunkZ;
        private final List<long[]> entries = new ArrayList<>(); // {key, materialId, flags}

        ChunkBuilder(CompactSpans owner, int chunkX, int chunkZ) {
            this.owner = owner;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public ChunkBuilder add(int x, int y, int z, String material, int flags) {
            entries.add(new long[] {BlockKey.pack(x, y, z), owner.materialId(material), flags});
            return this;
        }

        public int size() {
            return entries.size();
        }

        /** Stores the chunk (replacing any earlier extraction of it) and returns its span count. */
        public int build() {
            entries.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] keys = new long[entries.size()];
            short[] materials = new short[entries.size()];
            byte[] flags = new byte[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                long[] e = entries.get(i);
                keys[i] = e[0];
                materials[i] = (short) e[1];
                flags[i] = (byte) e[2];
            }
            owner.chunks.put(chunkKey(chunkX, chunkZ), new ChunkSpans(keys, materials, flags));
            owner.spanCount += keys.length;
            return keys.length;
        }
    }

    private final Map<Long, ChunkSpans> chunks = new HashMap<>();
    private final Map<String, Short> materialIds = new HashMap<>();
    private final List<String> materialNames = new ArrayList<>();
    private int spanCount;

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    public static int chunkZ(long chunkKey) {
        return (int) chunkKey;
    }

    public ChunkBuilder chunk(int chunkX, int chunkZ) {
        return new ChunkBuilder(this, chunkX, chunkZ);
    }

    /** Marks a chunk as captured with no spans (a chunk without roads, or an ungenerated one). */
    public void markEmpty(int chunkX, int chunkZ) {
        chunks.putIfAbsent(chunkKey(chunkX, chunkZ), new ChunkSpans(new long[0], new short[0], new byte[0]));
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return chunks.containsKey(chunkKey(chunkX, chunkZ));
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int spanCount() {
        return spanCount;
    }

    short materialId(String material) {
        Short id = materialIds.get(material);
        if (id == null) {
            if (materialNames.size() >= Short.MAX_VALUE) {
                throw new IllegalStateException("too many distinct materials");
            }
            id = (short) materialNames.size();
            materialIds.put(material, id);
            materialNames.add(material);
        }
        return id;
    }

    /** Whether {@code (x, y, z)} is a stored span. */
    public boolean isSpan(int x, int y, int z) {
        return index(x, y, z) >= 0;
    }

    /** The span's floor material, or null when it is not a span. */
    public String material(int x, int y, int z) {
        ChunkSpans c = chunks.get(chunkKey(x >> 4, z >> 4));
        if (c == null) {
            return null;
        }
        int i = c.indexOf(BlockKey.pack(x, y, z));
        return i < 0 ? null : materialNames.get(c.materials[i]);
    }

    /** The span's flags, or -1 when it is not a span. */
    public int flags(int x, int y, int z) {
        ChunkSpans c = chunks.get(chunkKey(x >> 4, z >> 4));
        if (c == null) {
            return -1;
        }
        int i = c.indexOf(BlockKey.pack(x, y, z));
        return i < 0 ? -1 : c.flags[i] & 0xFF;
    }

    public boolean hasFlag(int x, int y, int z, int flag) {
        int f = flags(x, y, z);
        return f >= 0 && (f & flag) != 0;
    }

    private int index(int x, int y, int z) {
        ChunkSpans c = chunks.get(chunkKey(x >> 4, z >> 4));
        return c == null ? -1 : c.indexOf(BlockKey.pack(x, y, z));
    }

    /**
     * The chunks a build still needs: neighbours (8-connected) of every captured chunk that holds a span on
     * its border, inside the region, not captured yet. Over-captures compared to the builder's exact BFS
     * (any border span counts, reachable or not) but is bounded by the region.
     */
    public Set<Long> frontierChunks(int minX, int minZ, int maxX, int maxZ) {
        Set<Long> frontier = new HashSet<>();
        for (Map.Entry<Long, ChunkSpans> entry : chunks.entrySet()) {
            ChunkSpans c = entry.getValue();
            if (c.keys.length == 0) {
                continue;
            }
            int cx = chunkX(entry.getKey());
            int cz = chunkZ(entry.getKey());
            boolean west = false;
            boolean east = false;
            boolean north = false;
            boolean south = false;
            for (long key : c.keys) {
                int lx = BlockKey.x(key) & 15;
                int lz = BlockKey.z(key) & 15;
                west |= lx == 0;
                east |= lx == 15;
                north |= lz == 0;
                south |= lz == 15;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if ((dx < 0 && !west) || (dx > 0 && !east) || (dz < 0 && !north) || (dz > 0 && !south)) {
                        continue;
                    }
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (chunks.containsKey(chunkKey(nx, nz))) {
                        continue;
                    }
                    // the neighbour chunk must overlap the region
                    if ((nx << 4) + 15 < minX || (nx << 4) > maxX || (nz << 4) + 15 < minZ || (nz << 4) > maxZ) {
                        continue;
                    }
                    frontier.add(chunkKey(nx, nz));
                }
            }
        }
        return frontier;
    }

    /** Chunks covering the region's border cells' neighbourhood of a block position (3×3 around it). */
    public static Set<Long> chunksAround(int blockX, int blockZ, int chunkRadius) {
        Set<Long> keys = new HashSet<>();
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                keys.add(chunkKey(cx + dx, cz + dz));
            }
        }
        return keys;
    }
}
