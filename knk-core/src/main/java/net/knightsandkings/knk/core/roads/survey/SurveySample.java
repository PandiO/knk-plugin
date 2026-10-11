package net.knightsandkings.knk.core.roads.survey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One sample of a survey walk (DESIGN §5.3): the floor under the admin's feet, the overlay lying on
 * it, and the floor material of the cross-section perpendicular to the walking direction at lateral
 * offsets {@value #MIN_OFFSET} … {@value #MAX_OFFSET}.
 * <p>Bukkit-free: materials are plain names as the paper side reads them with {@code Material.name()};
 * the sample never sees a Bukkit type.
 *
 * @param x        block x of the admin's feet
 * @param y        block y of the floor under the feet
 * @param z        block z of the admin's feet
 * @param onGround whether the admin was standing on that floor (Phase 3 only samples on the ground;
 *                 the flag is carried for the breadcrumb, the statistics do not filter on it)
 * @param floor    material of the floor under the feet, looking through overlays; never null and
 *                 always equal to {@code offsets.get(CENTRE_INDEX)}
 * @param overlay  material lying on the floor (snow layer, carpet, rail …) or null
 * @param offsets  floor material per lateral offset, index {@code i} = offset {@code i + MIN_OFFSET}
 *                 (so index {@value #CENTRE_INDEX} is offset 0); null = no standable cell there
 *                 (a wall, a drop, the void); exactly {@value #WIDTH} entries; unmodifiable
 */
public record SurveySample(int x, int y, int z, boolean onGround, String floor, String overlay,
                           List<String> offsets) {

    /** Leftmost lateral offset of the cross-section. */
    public static final int MIN_OFFSET = -7;
    /** Rightmost lateral offset of the cross-section. */
    public static final int MAX_OFFSET = 7;
    /** Number of cells in the cross-section. */
    public static final int WIDTH = MAX_OFFSET - MIN_OFFSET + 1;
    /** Index of offset 0 in {@link #offsets()}. */
    public static final int CENTRE_INDEX = -MIN_OFFSET;

    public SurveySample {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(offsets, "offsets");
        if (offsets.size() != WIDTH) {
            throw new IllegalArgumentException("offsets must have " + WIDTH + " entries (offsets "
                + MIN_OFFSET + ".." + MAX_OFFSET + "), got " + offsets.size());
        }
        if (!floor.equals(offsets.get(CENTRE_INDEX))) {
            throw new IllegalArgumentException("floor (" + floor + ") must equal the cross-section at offset 0 ("
                + offsets.get(CENTRE_INDEX) + ")");
        }
        offsets = Collections.unmodifiableList(new ArrayList<>(offsets));
    }

    /**
     * Convenience factory: the floor is taken from the cross-section's centre cell.
     *
     * @throws IllegalArgumentException when the centre cell is null or the list has the wrong size
     */
    public static SurveySample of(int x, int y, int z, boolean onGround, String overlay, List<String> offsets) {
        Objects.requireNonNull(offsets, "offsets");
        if (offsets.size() != WIDTH) {
            throw new IllegalArgumentException("offsets must have " + WIDTH + " entries, got " + offsets.size());
        }
        String centre = offsets.get(CENTRE_INDEX);
        if (centre == null) {
            throw new IllegalArgumentException("the cross-section's centre cell (offset 0) is the floor and cannot be null");
        }
        return new SurveySample(x, y, z, onGround, centre, overlay, offsets);
    }

    /** Floor material at a lateral offset ({@value #MIN_OFFSET} … {@value #MAX_OFFSET}); null = no standable cell. */
    public String materialAt(int offset) {
        if (offset < MIN_OFFSET || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset " + offset + " outside " + MIN_OFFSET + ".." + MAX_OFFSET);
        }
        return offsets.get(offset - MIN_OFFSET);
    }

    /** True when an overlay lies on the floor. */
    public boolean hasOverlay() {
        return overlay != null;
    }
}
