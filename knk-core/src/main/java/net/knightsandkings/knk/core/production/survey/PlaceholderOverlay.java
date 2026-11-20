package net.knightsandkings.knk.core.production.survey;

import java.util.HashMap;
import java.util.Map;

/**
 * Port: blocks waiting to regenerate (PRODUCTION_KINDS § 3.2 "Placeholders", § 7). A survey measures the intended
 * state, so where a regeneration job holds a placeholder (depleted stone, a replanted crop, a sapling for a felled
 * tree) the survey reads the original block instead. The regeneration jobs come with harvesting (P8,
 * PandiO/knk-workspace#134); until then the overlay is {@link #NONE}. A felled tree's job lists every original log and
 * leaves block, so the tree counts again as a whole.
 */
@FunctionalInterface
public interface PlaceholderOverlay {

    /** The original material waiting to come back at (x, y, z) of {@code world}, or null. Thread-safe. */
    String originalAt(String world, int x, int y, int z);

    PlaceholderOverlay NONE = (world, x, y, z) -> null;

    /** A fixed overlay for one world (tests, scenarios). */
    static PlaceholderOverlay of(String world, Map<Long, String> originals) {
        Map<Long, String> copy = new HashMap<>(originals);
        return (w, x, y, z) -> world.equals(w) ? copy.get(key(x, y, z)) : null;
    }

    /** Packs a block position (x and z within ±33 million, y within ±2048). */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
