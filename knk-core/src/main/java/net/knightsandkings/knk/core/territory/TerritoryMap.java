package net.knightsandkings.knk.core.territory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One world's territory chunk cache (territory DESIGN § 1): most chunks belong wholly to one verdict (one holder, or a
 * contested set); chunks a border crosses are <b>split</b> and keep a verdict per block column, so borders can run
 * diagonally. Chunks no holder reaches are not stored (wilderness). Immutable once built ({@link TerritoryCacheBuilder}),
 * so it is read from any thread.
 */
public final class TerritoryMap {

    /** Palette index of {@link TerritoryVerdict#WILDERNESS}. */
    public static final int WILDERNESS = 0;

    private final String world;
    private final List<TerritoryVerdict> palette;
    private final Map<Long, Integer> whole;
    private final Map<Long, short[]> split;

    TerritoryMap(String world, List<TerritoryVerdict> palette, Map<Long, Integer> whole, Map<Long, short[]> split) {
        this.world = world;
        this.palette = List.copyOf(palette);
        this.whole = whole;
        this.split = split;
    }

    /** The chunk key of chunk ({@code cx}, {@code cz}). */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /** The index of block column ({@code x}, {@code z}) in its chunk's column array. */
    public static int columnIndex(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    public String world() {
        return world;
    }

    /** The verdict at block column ({@code x}, {@code z}). */
    public TerritoryVerdict verdictAt(int x, int z) {
        return palette.get(indexAt(x, z));
    }

    /** The palette index at block column ({@code x}, {@code z}); {@link #WILDERNESS} when no holder reaches it. */
    public int indexAt(int x, int z) {
        long key = chunkKey(x >> 4, z >> 4);
        Integer index = whole.get(key);
        if (index != null) {
            return index;
        }
        short[] columns = split.get(key);
        return columns == null ? WILDERNESS : columns[columnIndex(x, z)];
    }

    /** Whether the chunk is split (its columns differ), so a lookup inside it must be made per block column. */
    public boolean isSplit(int cx, int cz) {
        return split.containsKey(chunkKey(cx, cz));
    }

    /** The palette index of a whole chunk, {@link #WILDERNESS} for one not stored, -1 for a split chunk. */
    public int wholeIndex(long key) {
        Integer index = whole.get(key);
        if (index != null) {
            return index;
        }
        return split.containsKey(key) ? -1 : WILDERNESS;
    }

    /** The column indexes of a split chunk (256, see {@link #columnIndex}), or null when the chunk is not split. */
    short[] columns(long key) {
        return split.get(key);
    }

    public TerritoryVerdict verdict(int paletteIndex) {
        return palette.get(paletteIndex);
    }

    public List<TerritoryVerdict> palette() {
        return palette;
    }

    public Set<Long> wholeChunks() {
        return Collections.unmodifiableSet(whole.keySet());
    }

    public Set<Long> splitChunks() {
        return Collections.unmodifiableSet(split.keySet());
    }

    /** Whole and split chunks together. */
    public int chunkCount() {
        return whole.size() + split.size();
    }

    public int splitCount() {
        return split.size();
    }

    /** Every verdict a chunk holds (one for a whole chunk, the distinct ones of a split chunk). */
    public Set<TerritoryVerdict> verdictsIn(long key) {
        Integer index = whole.get(key);
        if (index != null) {
            return Set.of(palette.get(index));
        }
        short[] columns = split.get(key);
        if (columns == null) {
            return Set.of(TerritoryVerdict.WILDERNESS);
        }
        java.util.LinkedHashSet<TerritoryVerdict> all = new java.util.LinkedHashSet<>();
        for (short column : columns) {
            all.add(palette.get(column));
        }
        return all;
    }
}
