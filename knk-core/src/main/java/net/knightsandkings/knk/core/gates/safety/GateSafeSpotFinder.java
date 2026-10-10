package net.knightsandkings.knk.core.gates.safety;

import java.util.Optional;

import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder.Spot;
import net.knightsandkings.knk.core.util.BlockProbe;

/**
 * A standing spot next to a gate door (KNG-105 {@code /gatedoor tp}, KNG-106 moving an entity out of
 * a door's way). Bukkit-free; the world comes in as a {@link BlockProbe}.
 * <p>
 * A spot is standable when {@link SafeLocationFinder#isSafe} holds (feet and head passable, solid
 * ground below, no lava/fire/cactus..., inside the world's height) <em>and</em> none of the feet,
 * head and ground cells is {@link CellFilter#blocked blocked} - the caller blocks the door's own
 * region, the cells a moving door sweeps through and other doors' blocks, so nobody is put inside a
 * door or onto a block that is about to move away.
 * <p>
 * Of the standable spots within {@link Query#radius} blocks (horizontally) of {@link Query#near},
 * the one with the lowest score wins: horizontal distance to {@code near}, plus 0.75 per block of
 * height difference from {@link Query#feetY}, plus a penalty for standing beside the door instead of
 * in front of or behind it, plus a larger one for the side opposite {@link Query#preferredSide}.
 * Ties keep the first spot found (lowest x, then z, then y), so the result is deterministic.
 */
public final class GateSafeSpotFinder {

    /** Standing beside the door (off its front/back faces). */
    static final double BESIDE_PENALTY = 2.0;
    /** Standing on the side of the door opposite the preferred one. */
    static final double WRONG_SIDE_PENALTY = 4.0;
    static final double HEIGHT_WEIGHT = 0.75;

    /** A block the spot's feet, head or ground must not be in. */
    @FunctionalInterface
    public interface CellFilter {
        boolean blocked(int x, int y, int z);

        CellFilter NONE = (x, y, z) -> false;

        /** Blocked when either filter blocks the cell. */
        default CellFilter or(CellFilter other) {
            return (x, y, z) -> blocked(x, y, z) || other.blocked(x, y, z);
        }

        /** Every block whose cube overlaps the box's inside (touching a face doesn't count). */
        static CellFilter inside(GateBox box) {
            return (x, y, z) -> box != null && overlaps(box, x, y, z);
        }
    }

    /**
     * What to search for.
     *
     * @param near          where to stay close to: the door's region (teleport) or the entity's box (push)
     * @param door          the door's box, for front/back/side; null = {@code near}
     * @param feetY         the feet height to prefer (the door's bottom, the entity's feet)
     * @param axisX         the door's horizontal face axis (x), 0 with {@code axisZ} 0 for none
     * @param axisZ         the door's horizontal face axis (z)
     * @param preferredSide +1 / -1: prefer the side the face axis points to / away from; 0: either face
     * @param radius        how far (blocks, horizontally) from {@code near} to look
     * @param verticalRange how far above and below {@code feetY} to look
     */
    public record Query(GateBox near, GateBox door, double feetY, double axisX, double axisZ, int preferredSide,
                        int radius, int verticalRange) {
        public Query {
            if (near == null) {
                throw new IllegalArgumentException("near must not be null");
            }
            door = door == null ? near : door;
            double length = Math.sqrt(axisX * axisX + axisZ * axisZ);
            if (length > 1e-9) {
                axisX /= length;
                axisZ /= length;
            } else {
                axisX = 0;
                axisZ = 0;
            }
            preferredSide = Integer.signum(preferredSide);
            radius = Math.max(0, radius);
            verticalRange = Math.max(0, verticalRange);
        }

        boolean hasAxis() {
            return axisX != 0 || axisZ != 0;
        }
    }

    private GateSafeSpotFinder() {
    }

    public static Optional<Spot> find(BlockProbe probe, Query query, CellFilter blocked) {
        GateBox near = query.near();
        int minX = (int) Math.floor(near.minX()) - query.radius();
        int maxX = (int) Math.ceil(near.maxX()) - 1 + query.radius();
        int minZ = (int) Math.floor(near.minZ()) - query.radius();
        int maxZ = (int) Math.ceil(near.maxZ()) - 1 + query.radius();
        int baseY = (int) Math.floor(query.feetY());

        Spot best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double horizontal = horizontalDistance(near, x + 0.5, z + 0.5);
                if (horizontal > query.radius() + 0.5) {
                    continue;
                }
                double sidePenalty = sidePenalty(query, x + 0.5, z + 0.5);
                for (int y = baseY - query.verticalRange(); y <= baseY + query.verticalRange(); y++) {
                    double score = horizontal + HEIGHT_WEIGHT * Math.abs(y - query.feetY()) + sidePenalty;
                    if (score >= bestScore || !isStandable(probe, x, y, z, blocked)) {
                        continue;
                    }
                    best = new Spot(x, y, z);
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@link SafeLocationFinder#isSafe} and none of feet, head and ground blocked. */
    public static boolean isStandable(BlockProbe probe, int x, int y, int z, CellFilter blocked) {
        CellFilter filter = blocked == null ? CellFilter.NONE : blocked;
        return !filter.blocked(x, y, z) && !filter.blocked(x, y + 1, z) && !filter.blocked(x, y - 1, z)
            && SafeLocationFinder.isSafe(probe, x, y, z);
    }

    /**
     * Which side of the door a point is on along the face axis: +1 where the axis points, -1 the
     * other way, 0 in the door's middle plane (or without an axis).
     */
    public static int sideOf(GateBox door, double axisX, double axisZ, double x, double z) {
        double along = (x - centerX(door)) * axisX + (z - centerZ(door)) * axisZ;
        if (Math.abs(along) < 1e-6) {
            return 0;
        }
        return along > 0 ? 1 : -1;
    }

    private static double sidePenalty(Query query, double x, double z) {
        if (!query.hasAxis()) {
            return 0;
        }
        GateBox door = query.door();
        double dx = x - centerX(door);
        double dz = z - centerZ(door);
        double along = dx * query.axisX() + dz * query.axisZ();
        double across = -dx * query.axisZ() + dz * query.axisX();
        double halfX = (door.maxX() - door.minX()) / 2;
        double halfZ = (door.maxZ() - door.minZ()) / 2;
        double halfAlong = Math.abs(halfX * query.axisX()) + Math.abs(halfZ * query.axisZ());
        double halfAcross = Math.abs(halfX * query.axisZ()) + Math.abs(halfZ * query.axisX());

        double penalty = 0;
        boolean facing = Math.abs(along) >= halfAlong && Math.abs(across) <= halfAcross + 0.5;
        if (!facing) {
            penalty += BESIDE_PENALTY;
        }
        if (query.preferredSide() != 0 && Math.signum(along) != query.preferredSide()) {
            penalty += WRONG_SIDE_PENALTY;
        }
        return penalty;
    }

    private static double horizontalDistance(GateBox box, double x, double z) {
        double dx = x - Math.max(box.minX(), Math.min(box.maxX(), x));
        double dz = z - Math.max(box.minZ(), Math.min(box.maxZ(), z));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double centerX(GateBox box) {
        return (box.minX() + box.maxX()) / 2;
    }

    private static double centerZ(GateBox box) {
        return (box.minZ() + box.maxZ()) / 2;
    }

    static boolean overlaps(GateBox box, int x, int y, int z) {
        return x < box.maxX() && x + 1 > box.minX()
            && y < box.maxY() && y + 1 > box.minY()
            && z < box.maxZ() && z + 1 > box.minZ();
    }
}
