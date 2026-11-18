package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.util.BlockKey;

/**
 * The clearance of a standing walk cell (off-road navigation phase 6.1, PandiO/knk-workspace#710, DESIGN § 7.1.4): the
 * distance from the cell's centre to the nearest obstacle column's square, capped at {@code cap}. An obstacle for a
 * cell on floor {@code y} is a column with no standing cell at {@code y - 1}, {@code y} or {@code y + 1}: a wall, a
 * post, a drop or another level, the rule the shaper's clearance map ({@code navigation.shape.Corridor}) judges a cell
 * by. The walk search adds {@link MovementProfile#clearanceCost()} per block the clearance lacks to
 * {@link MovementProfile#wallClearance()}, as the NPC local-leg search does.
 *
 * <p>Pure; one instance per search (it memoises per cell and is not thread-safe).
 */
public final class WalkClearance {

    /** Whether a floor block is a standing cell the mover may enter (walkable, with headroom, access allowing). */
    @FunctionalInterface
    public interface Standing {
        boolean at(int x, int floorY, int z);
    }

    private final Standing standing;
    private final double cap;
    private final int reach;
    private final LongMap<Boolean> open = new LongMap<>(1024);
    private final LongMap<Double> clearance = new LongMap<>(1024);

    /**
     * @param standing the standing-cell rule (the search's own, or the shaper adapter's)
     * @param cap      the largest clearance worked out ({@code wall-clearance}: more is never needed)
     */
    public WalkClearance(Standing standing, double cap) {
        if (!(cap > 0) || Double.isInfinite(cap)) {
            throw new IllegalArgumentException("cap must be a finite number > 0: " + cap);
        }
        this.standing = standing;
        this.cap = cap;
        // a column k rings away is at least k - 0.5 from the centre
        this.reach = (int) Math.ceil(cap + 0.5);
    }

    /** The clearance of the standing cell on floor {@code (x, y, z)}, at most the cap. */
    public double of(int x, int y, int z) {
        long key = BlockKey.pack(x, y, z);
        Double known = clearance.get(key);
        if (known != null) {
            return known;
        }
        double best = cap;
        double cx = x + 0.5;
        double cz = z + 0.5;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int ox = x + dx;
                int oz = z + dz;
                double ddx = Math.max(Math.max(ox - cx, 0), cx - (ox + 1));
                double ddz = Math.max(Math.max(oz - cz, 0), cz - (oz + 1));
                double d = Math.hypot(ddx, ddz);
                if (d < best && !openFor(ox, y, oz)) {
                    best = d;
                }
            }
        }
        clearance.put(key, best);
        return best;
    }

    /** How much the cell lacks to {@code wallClearance} (0 when it has at least that much). */
    public double lack(int x, int y, int z, double wallClearance) {
        return Math.max(0, wallClearance - of(x, y, z));
    }

    /** Whether the column {@code (x, z)} has a standing cell within a step of floor {@code y}. */
    private boolean openFor(int x, int y, int z) {
        long key = BlockKey.pack(x, y, z);
        Boolean known = open.get(key);
        if (known != null) {
            return known;
        }
        boolean result = standing.at(x, y, z) || standing.at(x, y - 1, z) || standing.at(x, y + 1, z);
        open.put(key, result);
        return result;
    }
}
