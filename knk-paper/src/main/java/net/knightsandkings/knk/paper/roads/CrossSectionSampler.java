package net.knightsandkings.knk.paper.roads;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.survey.SurveySample;

/**
 * Builds one {@link SurveySample} from the world around a surveying admin (DESIGN §5.3): the floor under
 * the feet (through overlays), the overlay on it, and the floor material at lateral offsets −7…+7
 * perpendicular to the walking direction, each at the standable height nearest the previous column's
 * (|Δy| ≤ 1 per step, stopping at walls). Pure: blocks come through {@link Blocks}, so the world and a
 * test fake look alike. At most 15 columns per sample are read.
 */
public final class CrossSectionSampler {

    /** Material name (upper case) at a world position. */
    @FunctionalInterface
    public interface Blocks {
        String materialAt(int x, int y, int z);
    }

    /** A standable column: the floor block and, if any, the overlay lying on it. */
    public record Floor(int y, String material, String overlay) {
    }

    private final PassabilityRules rules;
    private final int minY;
    private final int maxY; // exclusive
    private final int halfWidth;

    public CrossSectionSampler(PassabilityRules rules, int minY, int maxY, int halfWidth) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.minY = minY;
        this.maxY = maxY;
        this.halfWidth = Math.max(1, Math.min(SurveySample.MAX_OFFSET, halfWidth));
    }

    /**
     * The floor block (the network's y convention: a player standing there has feet at {@code y + 1})
     * for a player whose feet are at {@code feetY}: what {@link #floorUnder} finds from
     * {@code floor(feetY − ε)} - a full block, a slab, a dirt path or the block under a carpet/snow
     * layer - else, mid-air (climbing a ladder, a jump), the block under the feet block.
     */
    public int floorY(Blocks blocks, int x, double feetY, int z) {
        int standY = (int) Math.floor(feetY - 0.001);
        return floorUnder(blocks, x, standY, z).map(Floor::y).orElse((int) Math.floor(feetY) - 1);
    }

    /**
     * The floor the admin stands on: the block under the feet, or the block under an overlay there.
     * {@code feetY} is the feet block ({@code floor(y − ε)}). Empty when there is no solid floor within 2
     * blocks below (the admin is mid-air).
     */
    public Optional<Floor> floorUnder(Blocks blocks, int x, int feetY, int z) {
        // The feet block may hold an overlay (carpet, snow layer) or be air above the floor.
        String atFeet = at(blocks, x, feetY, z);
        String overlay = null;
        int y = feetY;
        if (rules.isOverlay(atFeet)) {
            overlay = atFeet;
            y = feetY - 1;
        } else if (rules.isSolid(atFeet) && !rules.isOverlay(atFeet)) {
            // Standing "in" a slab/stair: that block is the floor.
            return Optional.of(new Floor(feetY, atFeet, null));
        } else {
            y = feetY - 1;
        }
        for (int tries = 0; tries < 2; tries++, y--) {
            if (y < minY) {
                return Optional.empty();
            }
            String m = at(blocks, x, y, z);
            if (rules.isOverlay(m)) {
                overlay = m;
                continue;
            }
            if (rules.isSolid(m)) {
                return Optional.of(new Floor(y, m, overlay));
            }
        }
        return Optional.empty();
    }

    /**
     * The cross-section through {@code (x, floorY, z)} along the lateral unit vector {@code lateral}
     * ({@code {dx, dz}}): 15 entries for offsets −7…+7 (null where no standable floor within |Δy| ≤ 1 of the
     * previous column, and every offset beyond it on that side), offset 0 being {@code centre}.
     */
    public SurveySample sample(Blocks blocks, int x, int floorY, int z, Floor centre, double[] lateral) {
        String[] offsets = new String[SurveySample.WIDTH];
        offsets[SurveySample.CENTRE_INDEX] = centre.material();
        for (int side = -1; side <= 1; side += 2) {
            int prevY = floorY;
            for (int step = 1; step <= halfWidth; step++) {
                int off = side * step;
                int cx = x + (int) Math.round(lateral[0] * off);
                int cz = z + (int) Math.round(lateral[1] * off);
                Optional<Floor> floor = standableNear(blocks, cx, prevY, cz);
                if (floor.isEmpty()) {
                    break; // a wall or a drop: this side of the section ends
                }
                offsets[SurveySample.CENTRE_INDEX + off] = floor.get().material();
                prevY = floor.get().y();
            }
        }
        return SurveySample.of(x, floorY, z, true, centre.overlay(), Arrays.asList(offsets));
    }

    /** A standable floor at {@code y}, {@code y + 1} or {@code y − 1} (in that order) in the column. */
    Optional<Floor> standableNear(Blocks blocks, int x, int y, int z) {
        for (int dy : new int[] {0, 1, -1}) {
            Optional<Floor> f = standableAt(blocks, x, y + dy, z);
            if (f.isPresent()) {
                return f;
            }
        }
        return Optional.empty();
    }

    /** A solid non-overlay floor at {@code y} with two passable blocks above (an overlay at y+1 is recorded). */
    Optional<Floor> standableAt(Blocks blocks, int x, int y, int z) {
        if (y < minY || y + 2 >= maxY) {
            return Optional.empty();
        }
        String floor = at(blocks, x, y, z);
        if (rules.isOverlay(floor) || !rules.isSolid(floor)) {
            return Optional.empty();
        }
        String above1 = at(blocks, x, y + 1, z);
        String above2 = at(blocks, x, y + 2, z);
        if (!rules.isPassable(above1) || !rules.isPassable(above2)) {
            return Optional.empty();
        }
        return Optional.of(new Floor(y, floor, rules.isOverlay(above1) ? above1 : null));
    }

    private static String at(Blocks blocks, int x, int y, int z) {
        String m = blocks.materialAt(x, y, z);
        return m == null ? "AIR" : m;
    }
}
