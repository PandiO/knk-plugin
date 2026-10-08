package net.knightsandkings.knk.core.gates.target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The geometry behind the gate commands' implicit targets, free of Bukkit so it can be tested on
 * its own:
 * <ul>
 *   <li>KNG-78 {@code here}: the doors whose region lies within a radius of the player, measured to
 *       the region's closest point (inside = 0), and the same grouped by gate structure;</li>
 *   <li>KNG-79 look-at: the door region the player's line of sight enters first, so an open gate
 *       (no blocks in the opening) is found as well as a closed one.</li>
 * </ul>
 * Both only run when a command runs, never per tick or on tab completion.
 */
public final class GateTargetMath {
    /** Slack between the first solid block the ray hits and the door box that holds that block. */
    static final double BLOCK_HIT_SLACK = 0.05;

    private GateTargetMath() {
    }

    /** One door's region: the box its closed blocks and captured regions span, in one world. */
    public record DoorRegion(int doorId, int structureId, String worldName, GateBox box) {
        public DoorRegion {
            Objects.requireNonNull(box, "box");
        }
    }

    /** A door within reach, {@code distance} blocks from the player (0 = inside its region). */
    public record DoorCandidate(int doorId, int structureId, double distance) {
    }

    /** A gate structure within reach: its nearest door's distance, and its doors in reach. */
    public record StructureCandidate(int structureId, double distance, List<Integer> doorIds) {
    }

    /**
     * Doors in {@code worldName} whose region is within {@code radius} of the point, nearest first
     * (ties by id, so the order is stable).
     */
    public static List<DoorCandidate> doorsWithin(List<DoorRegion> regions, String worldName,
                                                   double x, double y, double z, double radius) {
        List<DoorCandidate> found = new ArrayList<>();
        for (DoorRegion region : regions) {
            if (!Objects.equals(region.worldName(), worldName)) {
                continue;
            }
            double distance = region.box().distanceTo(x, y, z);
            if (distance <= radius) {
                found.add(new DoorCandidate(region.doorId(), region.structureId(), distance));
            }
        }
        found.sort(Comparator.comparingDouble(DoorCandidate::distance).thenComparingInt(DoorCandidate::doorId));
        return found;
    }

    /**
     * {@link #doorsWithin} grouped by gate structure: several doors of one gate are one candidate
     * at their nearest door's distance. Nearest first.
     */
    public static List<StructureCandidate> structuresWithin(List<DoorRegion> regions, String worldName,
                                                            double x, double y, double z, double radius) {
        Map<Integer, List<DoorCandidate>> byStructure = new LinkedHashMap<>();
        for (DoorCandidate door : doorsWithin(regions, worldName, x, y, z, radius)) {
            byStructure.computeIfAbsent(door.structureId(), id -> new ArrayList<>()).add(door);
        }
        List<StructureCandidate> found = new ArrayList<>();
        byStructure.forEach((structureId, doors) -> found.add(new StructureCandidate(structureId,
            doors.get(0).distance(), doors.stream().map(DoorCandidate::doorId).toList())));
        found.sort(Comparator.comparingDouble(StructureCandidate::distance).thenComparingInt(StructureCandidate::structureId));
        return found;
    }

    /**
     * The door whose region the line of sight enters first, if any.
     *
     * @param direction       view direction, need not be normalised
     * @param maxDistance     how far the player can "point" (gates.lookat.max-distance)
     * @param blockHitDistance distance to the first solid block on the ray, or null when nothing
     *                        was hit within {@code maxDistance}. A door behind that block is
     *                        hidden by it; a closed door's own block is inside its region, so the
     *                        region is entered no later than the hit.
     * @return the door, with {@link DoorCandidate#distance()} the distance along the ray
     */
    public static Optional<DoorCandidate> lookedAt(List<DoorRegion> regions, String worldName,
                                                   double[] eye, double[] direction,
                                                   double maxDistance, Double blockHitDistance) {
        double length = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]);
        if (length < 1e-9) {
            return Optional.empty();
        }
        double dx = direction[0] / length;
        double dy = direction[1] / length;
        double dz = direction[2] / length;
        double reach = blockHitDistance == null ? maxDistance : Math.min(maxDistance, blockHitDistance + BLOCK_HIT_SLACK);

        DoorCandidate best = null;
        for (DoorRegion region : regions) {
            if (!Objects.equals(region.worldName(), worldName)) {
                continue;
            }
            OptionalDouble entry = region.box().rayEntry(eye[0], eye[1], eye[2], dx, dy, dz);
            if (entry.isEmpty() || entry.getAsDouble() > reach) {
                continue;
            }
            double t = entry.getAsDouble();
            if (best == null || t < best.distance() || (t == best.distance() && region.doorId() < best.doorId())) {
                best = new DoorCandidate(region.doorId(), region.structureId(), t);
            }
        }
        return Optional.ofNullable(best);
    }
}
