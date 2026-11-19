package net.knightsandkings.knk.core.roads.walk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a walk leg's last found path tells its next search (PandiO/knk-workspace#771 item 1). A recompute from inside
 * a building used to search only the box around the player and the target, and with the length cap of that short
 * straight distance; where the only way runs far outside that box (the keep: from the lower stair house flights to the
 * storage hall 13 blocks above, about 290 blocks back through the courtyard), every recompute ended NO_PATH, and its
 * partial path led the player to the dead end nearest the target. The hint keeps the rest of the found path in the
 * capture ({@link #bounds()}) and its length in the cap ({@link #minLength()}), so the search can take the same way again.
 *
 * @param remaining the found path from the player's nearest point on it to its end, as floor points
 * @param minLength the length cap the search needs at least: the remaining length, twice the player's distance from the
 *                  path, and {@link #SLACK}
 */
public record WalkRecomputeHint(List<double[]> remaining, double minLength) {

    /** Blocks of length the hint allows beyond the found way (a few steps of detour back onto it). */
    public static final double SLACK = 8.0;

    public WalkRecomputeHint {
        Objects.requireNonNull(remaining, "remaining");
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("remaining must not be empty");
        }
        remaining = List.copyOf(remaining);
    }

    /**
     * The hint for a player at floor position {@code (x, floorY, z)} and a found path; empty without a path (null or
     * fewer than two points).
     */
    public static Optional<WalkRecomputeHint> of(List<double[]> path, double x, double floorY, double z) {
        if (path == null || path.size() < 2) {
            return Optional.empty();
        }
        double[] p = {x, floorY, z};
        int bestSegment = 0;
        double bestT = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < path.size(); i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double t = projection(a, b, p);
            double d = distance(lerp(a, b, t), p);
            if (d < best) {
                best = d;
                bestSegment = i;
                bestT = t;
            }
        }
        List<double[]> rest = new ArrayList<>();
        double[] start = lerp(path.get(bestSegment), path.get(bestSegment + 1), bestT);
        rest.add(start);
        double length = 0;
        double[] previous = start;
        for (int i = bestSegment + 1; i < path.size(); i++) {
            double[] q = path.get(i).clone();
            length += distance(previous, q);
            rest.add(q);
            previous = q;
        }
        return Optional.of(new WalkRecomputeHint(rest, length + 2 * best + SLACK));
    }

    /** The box of the remaining path's floor blocks: {@code {minX, minY, minZ, maxX, maxY, maxZ}}. */
    public int[] bounds() {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (double[] q : remaining) {
            for (int axis = 0; axis < 3; axis++) {
                int block = (int) Math.floor(q[axis]);
                box[axis] = Math.min(box[axis], block);
                box[axis + 3] = Math.max(box[axis + 3], block);
            }
        }
        return box;
    }

    /** {@code budget} with a cap of at least {@link #minLength()} ({@link WalkBudget#withLengthAtLeast}). */
    public WalkBudget widen(WalkBudget budget, double straight, double rise) {
        return Objects.requireNonNull(budget, "budget").withLengthAtLeast(straight, rise, minLength);
    }

    private static double projection(double[] a, double[] b, double[] p) {
        double len2 = 0;
        double dot = 0;
        for (int axis = 0; axis < 3; axis++) {
            double ab = b[axis] - a[axis];
            len2 += ab * ab;
            dot += (p[axis] - a[axis]) * ab;
        }
        return len2 == 0 ? 0 : Math.max(0, Math.min(1, dot / len2));
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
