package net.knightsandkings.knk.paper.navigation.walk;

import java.util.Arrays;

import net.knightsandkings.knk.core.util.BlockKey;

/**
 * One chunk captured for the walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §8): a band of
 * 16-high sections, every block reduced to a flags byte, plus the floor material of every block that
 * can carry a walk cell and the positions of the hand-openable doors. Immutable and Bukkit-free;
 * built on the main thread by {@link WalkChunkExtractor}, read by any thread through
 * {@link CapturedWalkTerrain}.
 *
 * <p>Why not the road builder's {@code CompactSpans}: the builder only ever asks about road spans and
 * their headroom, so it keeps just those. The walk search asks about <em>any</em> block near a
 * cell — drop columns down to {@code maxDrop} below in the neighbour columns, the block above a ladder,
 * water at the feet — and needs door/climbable/water flags {@code CompactSpans} does not store.
 *
 * <p>Memory: a section whose 4096 blocks all have the same flags (open air, solid rock with ores, a
 * lake bottom of water) is stored as that one byte; any other section as a {@code byte[4096]}.
 * Floor materials are a sorted {@code long[]} of block keys with a {@code short} palette index each
 * (usually about one per column plus caves and overhangs).
 */
public final class WalkChunk {

    /** A player can stand in this block (air, grass, water, an overlay …). */
    public static final int PASSABLE = 1;
    /** A player can stand on this block. Exact complement of {@link #PASSABLE} for captured blocks. */
    public static final int SOLID = 2;
    /** Standing in or on this block hurts. */
    public static final int HAZARD = 4;
    /** A stair or slab (no jump needed to step onto it). */
    public static final int STAIR_OR_SLAB = 8;
    /** A hand-openable door or fence gate. */
    public static final int DOOR = 16;
    /** A climbable (ladder). */
    public static final int CLIMBABLE = 32;
    /** Water or a bubble column. */
    public static final int WATER = 64;

    static final int SECTION_BLOCKS = 16 * 16 * 16;

    private final int chunkX;
    private final int chunkZ;
    private final int minSection;
    private final byte[][] sections;
    private final byte[] uniform;
    private final long[] floorKeys;
    private final short[] floorIds;
    private final String[] palette;
    private final long[] doors;
    private final long capturedAt;

    WalkChunk(int chunkX, int chunkZ, int minSection, byte[][] sections, byte[] uniform, long[] floorKeys,
              short[] floorIds, String[] palette, long[] doors, long capturedAt) {
        if (sections.length != uniform.length) {
            throw new IllegalArgumentException("sections and uniform differ in length");
        }
        if (floorKeys.length != floorIds.length) {
            throw new IllegalArgumentException("floor keys and ids differ in length");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minSection;
        this.sections = sections;
        this.uniform = uniform;
        this.floorKeys = floorKeys;
        this.floorIds = floorIds;
        this.palette = palette;
        this.doors = doors;
        this.capturedAt = capturedAt;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** Lowest captured section ({@code y >> 4}). */
    public int minSection() {
        return minSection;
    }

    /** Highest captured section, inclusive. */
    public int maxSection() {
        return minSection + sections.length - 1;
    }

    /** Whether every section from {@code from} to {@code to} (inclusive) is captured. */
    public boolean covers(int from, int to) {
        return from >= minSection && to <= maxSection();
    }

    /** When the chunk was captured (the cache's clock, milliseconds). */
    public long capturedAt() {
        return capturedAt;
    }

    /** Whether the block lies in this chunk's captured band. */
    public boolean contains(int x, int y, int z) {
        int s = y >> 4;
        return (x >> 4) == chunkX && (z >> 4) == chunkZ && s >= minSection && s <= maxSection();
    }

    /** The flags of a captured block; {@code 0} (neither passable nor solid) outside the band. */
    public int flags(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length) {
            return 0;
        }
        byte[] section = sections[s];
        if (section == null) {
            return uniform[s] & 0xFF;
        }
        return section[index(x, y, z)] & 0xFF;
    }

    /** The material of a block that can carry a walk cell, or null when the capture did not record one there. */
    public String floorMaterial(int x, int y, int z) {
        int i = Arrays.binarySearch(floorKeys, BlockKey.pack(x, y, z));
        return i < 0 ? null : palette[floorIds[i]];
    }

    /** Packed {@link BlockKey}s of every door block in the captured band (both halves of a door). */
    public long[] doorBlocks() {
        return doors.clone();
    }

    public int floorCount() {
        return floorKeys.length;
    }

    /** Sections stored as a full {@code byte[4096]} (the rest are one byte). */
    public int mixedSections() {
        int n = 0;
        for (byte[] section : sections) {
            if (section != null) {
                n++;
            }
        }
        return n;
    }

    /** Approximate heap bytes of the captured data (arrays only; for the §8 memory measurement). */
    public long approximateBytes() {
        long bytes = 64L + sections.length * 8L + uniform.length;
        bytes += (long) mixedSections() * (SECTION_BLOCKS + 16);
        bytes += floorKeys.length * 8L + floorIds.length * 2L + doors.length * 8L + 48;
        for (String name : palette) {
            bytes += 8 + 40 + name.length();
        }
        return bytes;
    }

    static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /** The cache key of a chunk position ({@code CompactSpans}' encoding). */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return "WalkChunk[" + chunkX + "," + chunkZ + " sections " + minSection + ".." + maxSection()
            + ", mixed " + mixedSections() + ", floors " + floorKeys.length + ", doors " + doors.length + "]";
    }
}
