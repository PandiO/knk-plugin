package net.knightsandkings.knk.core.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a spot near a teleport destination where a player can stand without getting hurt
 * (docs/specs/teleport/DESIGN.md §3.4.2) - the bounded replacement for v1's unused
 * {@code canTeleport}/{@code TeleportNearby} helpers, whose search recursed forever when nothing
 * was safe.
 * <p>
 * A spot is safe when the feet and head blocks are passable and not a hazard, the block below is
 * solid and not a hazard, and all three are inside the world's height range. The search tries the
 * exact spot first, then the exact column at dy +1, -1, +2, -2, then square rings of radius
 * 1..{@code radius} around it (nearest blocks of a ring first) at the same dy offsets. It gives up
 * after that - the caller refuses the teleport instead of dropping the player somewhere random.
 */
public final class SafeLocationFinder {

    /** Bukkit {@code Material} names that are never safe to stand in or on. */
    public static final Set<String> HAZARD_MATERIALS = Set.of(
        "LAVA", "MAGMA_BLOCK", "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS",
        "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "POINTED_DRIPSTONE"
    );

    private static final int[] DY_ORDER = {0, 1, -1, 2, -2};

    public record Spot(int x, int y, int z) {
    }

    private SafeLocationFinder() {
    }

    public static Optional<Spot> find(BlockProbe probe, int x, int y, int z, int radius) {
        for (int r = 0; r <= Math.max(0, radius); r++) {
            List<int[]> ring = ring(r);
            for (int dy : DY_ORDER) {
                for (int[] offset : ring) {
                    int cx = x + offset[0];
                    int cy = y + dy;
                    int cz = z + offset[1];
                    if (isSafe(probe, cx, cy, cz)) {
                        return Optional.of(new Spot(cx, cy, cz));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a player's feet can go at (x, y, z). */
    public static boolean isSafe(BlockProbe probe, int x, int y, int z) {
        if (y - 1 < probe.minY() || y + 1 >= probe.maxY()) {
            return false;
        }
        return probe.isPassable(x, y, z) && !probe.isHazard(x, y, z)
            && probe.isPassable(x, y + 1, z) && !probe.isHazard(x, y + 1, z)
            && probe.isSolid(x, y - 1, z) && !probe.isHazard(x, y - 1, z);
    }

    /** The (dx, dz) offsets at Chebyshev distance exactly r, nearest (Euclidean) first. */
    private static List<int[]> ring(int r) {
        List<int[]> offsets = new ArrayList<>();
        if (r == 0) {
            offsets.add(new int[] {0, 0});
            return offsets;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                    offsets.add(new int[] {dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return offsets;
    }
}
