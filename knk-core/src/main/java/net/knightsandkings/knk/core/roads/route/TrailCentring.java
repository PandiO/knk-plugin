package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * KNG-76: keeps a road trail in the middle of the road. Edge geometry is whole floor blocks, so on a road an even
 * number of blocks wide - the two-block stairs from Brink down to #3588 - the skeleton lies on one of the two middle
 * rows and the trail runs half a block off-centre, along the edge; and a straight line between two vertices cuts
 * the inside of a bend. Each trail point looks across the road (perpendicular to the way it goes) and moves to the
 * middle of the road cells it finds there, up to {@link #MAX_HALF_WIDTH} blocks each side, so a wide road (whose
 * skeleton is already central) is left alone. Where the trail climbs or drops, it moves to the middle of the stair
 * and slab cells across the road instead, when there are any. The shifts are smoothed over neighbouring points.
 * Only the drawn trail changes - not the graph, the route or its length. Pure; the world is the {@link Ground} port.
 */
public final class TrailCentring {

    /** How far across the road a point looks, each side (blocks). */
    public static final int MAX_HALF_WIDTH = 3;
    /** A point counts as on a slope when the trail around it rises or drops at least this much (blocks). */
    static final double SLOPE = 0.5;
    /** Shifts are averaged over this many points either side (a caller centring a window adds this margin). */
    public static final int SMOOTHING = 2;

    /** Port: the road surface near a trail point (main thread on the server). */
    public interface Ground {
        /**
         * The floor y of a road cell in column {@code (x, z)} at {@code nearY} or one block above or below it (a
         * road material a player can stand on, with room above), or empty: a wall, a drop, not road.
         */
        OptionalInt roadFloor(int x, int z, int nearY);

        /** Whether the floor block at {@code (x, y, z)} is a stair or a slab. */
        boolean stairOrSlab(int x, int y, int z);
    }

    private TrailCentring() {
    }

    /**
     * The trail points moved towards the middle of the road.
     *
     * @param points trail points (block-centred x/z, floor y), in walking order
     * @return as many points, same order; y unchanged
     */
    public static List<double[]> centre(List<double[]> points, Ground ground) {
        Objects.requireNonNull(ground, "ground");
        int n = points.size();
        List<double[]> out = new ArrayList<>(n);
        if (n < 2) {
            points.forEach(p -> out.add(p.clone()));
            return out;
        }
        double[] shift = new double[n];
        double[][] normal = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] before = points.get(Math.max(0, i - 1));
            double[] after = points.get(Math.min(n - 1, i + 1));
            double dx = after[0] - before[0];
            double dz = after[2] - before[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-9) {
                continue;
            }
            normal[i] = new double[] {-dz / len, dx / len};
            boolean sloped = Math.abs(after[1] - before[1]) >= SLOPE;
            shift[i] = offset(points.get(i), normal[i], sloped, ground);
        }
        for (int i = 0; i < n; i++) {
            double[] p = points.get(i).clone();
            if (normal[i] != null) {
                double sum = 0;
                int count = 0;
                for (int k = Math.max(0, i - SMOOTHING); k <= Math.min(n - 1, i + SMOOTHING); k++) {
                    if (normal[k] != null) {
                        sum += shift[k];
                        count++;
                    }
                }
                double s = sum / count;
                p[0] += normal[i][0] * s;
                p[2] += normal[i][1] * s;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * How far along {@code normal} the middle of the road lies from {@code p} (blocks): the road cells reachable from
     * the point's own cell across the road, each side until a non-road cell; on a slope the middle of the stair and
     * slab cells among them, if any. 0 when the point is not on a road cell.
     */
    static double offset(double[] p, double[] normal, boolean sloped, Ground ground) {
        int y = (int) Math.round(p[1]);
        int ownX = (int) Math.floor(p[0]);
        int ownZ = (int) Math.floor(p[2]);
        OptionalInt own = ground.roadFloor(ownX, ownZ, y);
        if (own.isEmpty()) {
            return 0;
        }
        double low = 0;
        double high = 0;
        double stairSum = 0; // the own cell sits at 0
        int stairs = ground.stairOrSlab(ownX, own.getAsInt(), ownZ) ? 1 : 0;
        for (int side = -1; side <= 1; side += 2) {
            int floor = own.getAsInt();
            int lastX = ownX;
            int lastZ = ownZ;
            for (int k = 1; k <= MAX_HALF_WIDTH; k++) {
                int x = (int) Math.floor(p[0] + side * normal[0] * k);
                int z = (int) Math.floor(p[2] + side * normal[1] * k);
                if (x == lastX && z == lastZ) {
                    continue;
                }
                OptionalInt cell = ground.roadFloor(x, z, floor);
                if (cell.isEmpty()) {
                    break;
                }
                floor = cell.getAsInt();
                lastX = x;
                lastZ = z;
                if (side < 0) {
                    low = -k;
                } else {
                    high = k;
                }
                if (ground.stairOrSlab(x, floor, z)) {
                    stairSum += side * k;
                    stairs++;
                }
            }
        }
        if (sloped && stairs > 0) {
            return stairSum / stairs;
        }
        return (low + high) / 2;
    }
}
