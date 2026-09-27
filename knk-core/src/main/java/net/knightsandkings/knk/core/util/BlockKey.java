package net.knightsandkings.knk.core.util;

/**
 * Packs a world block position into one {@code long} key and unpacks it again.
 *
 * <p>Layout (high to low bits): 26-bit x, 12-bit y, 26-bit z - the same layout
 * {@code GateSpatialIndex.packCell} has always used, so keys from either are interchangeable.
 * Every component is stored as its low bits and read back with sign extension, so negative
 * coordinates round-trip: x/z cover ±33.5 million blocks (beyond the world border), y covers
 * -2048..2047 (the 1.18+ world height of -64..319 with room to spare).
 *
 * <p>Bukkit-free on purpose: the road builder and router (knk-core {@code roads}/{@code navigation})
 * key their cell maps with it. {@code GateBlockScanTaskHandler.packCoordinate} in knk-paper is a
 * different, internal layout and is deliberately not unified with this one.
 */
public final class BlockKey {
    private static final int X_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_BITS = 26;

    private static final int X_SHIFT = Y_BITS + Z_BITS; // 38
    private static final int Y_SHIFT = Z_BITS;          // 26

    private static final long X_MASK = (1L << X_BITS) - 1; // 0x3FFFFFF
    private static final long Y_MASK = (1L << Y_BITS) - 1; // 0xFFF
    private static final long Z_MASK = (1L << Z_BITS) - 1; // 0x3FFFFFF

    private BlockKey() {
    }

    /**
     * Pack a block position. Components outside the bit widths wrap silently (same as the
     * original {@code packCell}); callers stay within world bounds.
     */
    public static long pack(int x, int y, int z) {
        return (((long) x & X_MASK) << X_SHIFT)
            | (((long) y & Y_MASK) << Y_SHIFT)
            | ((long) z & Z_MASK);
    }

    /** The x component of a packed key, sign-extended from its 26 bits. */
    public static int x(long key) {
        // x occupies the top 26 bits: an arithmetic shift right sign-extends it directly.
        return (int) (key >> X_SHIFT);
    }

    /** The y component of a packed key, sign-extended from its 12 bits. */
    public static int y(long key) {
        // Move y to the top 12 bits, then arithmetic-shift it back down to sign-extend.
        return (int) ((key << (Long.SIZE - Y_SHIFT - Y_BITS)) >> (Long.SIZE - Y_BITS));
    }

    /** The z component of a packed key, sign-extended from its 26 bits. */
    public static int z(long key) {
        return (int) ((key << (Long.SIZE - Z_BITS)) >> (Long.SIZE - Z_BITS));
    }

    /** The key of the block offset by {@code (dx, dy, dz)} from the block {@code key} names. */
    public static long neighbour(long key, int dx, int dy, int dz) {
        return pack(x(key) + dx, y(key) + dy, z(key) + dz);
    }
}
