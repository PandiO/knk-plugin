package net.knightsandkings.knk.core.roads.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

/**
 * The world tags of an edge's polyline (DESIGN §5 step 6, §6.7): the WorldGuard regions and the gate
 * doors it passes. The build tags detected edges once; a recorded stretch (KNG-27 live test
 * 2026-10-08, finding N3) and the live re-tagging of the whole network (finding N4: a region made or a
 * gate placed after the build) use the same sampling, so all three agree.
 *
 * <p>Pure: the polyline is the edge's floor blocks; the caller asks WorldGuard (regions at feet level,
 * {@code floor + 1}) and the {@link GateCells} itself.
 */
public final class EdgeTagging {

    /** Region samples are at most this far apart (the build samples every 4; a gate region can be thinner). */
    public static final double REGION_STEP = 2.0;
    /** Gate door samples are at most this far apart: a door is one block deep. */
    public static final double DOOR_STEP = 0.5;

    private EdgeTagging() {
    }

    /**
     * Floor blocks along the polyline, at most {@code step} apart, both ends included, consecutive
     * duplicates removed. Between two points the position is interpolated from block centre to block
     * centre; the floor y is rounded.
     */
    public static List<int[]> samples(List<int[]> geometry, double step) {
        Objects.requireNonNull(geometry, "geometry");
        if (!(step > 0)) {
            throw new IllegalArgumentException("step must be positive: " + step);
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < geometry.size(); i++) {
            int[] p = geometry.get(i);
            if (i == 0) {
                add(out, p[0], p[1], p[2]);
            }
            if (i + 1 >= geometry.size()) {
                continue;
            }
            int[] q = geometry.get(i + 1);
            double dx = q[0] - p[0];
            double dy = q[1] - p[1];
            double dz = q[2] - p[2];
            int n = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / step));
            for (int k = 1; k <= n; k++) {
                double t = (double) k / n;
                add(out, (int) Math.floor(p[0] + 0.5 + dx * t), (int) Math.floor(p[1] + dy * t + 0.5),
                    (int) Math.floor(p[2] + 0.5 + dz * t));
            }
        }
        return out;
    }

    private static void add(List<int[]> out, int x, int y, int z) {
        if (!out.isEmpty()) {
            int[] last = out.get(out.size() - 1);
            if (last[0] == x && last[1] == y && last[2] == z) {
                return;
            }
        }
        out.add(new int[] {x, y, z});
    }

    /**
     * The gate doors whose closed footprint the polyline passes, in the order it meets them: a door
     * block at the floor, the feet or the head of a sample (as the builder's span tagging).
     */
    public static List<Integer> doorsAlong(List<int[]> geometry, GateCells gates) {
        Objects.requireNonNull(gates, "gates");
        Set<Integer> doors = new LinkedHashSet<>();
        for (int[] s : samples(geometry, DOOR_STEP)) {
            for (int h = 0; h <= 2; h++) {
                OptionalInt door = gates.doorAt(s[0], s[1] + h, s[2]);
                if (door.isPresent()) {
                    doors.add(door.getAsInt());
                }
            }
        }
        return new ArrayList<>(doors);
    }

    /**
     * The edge with {@code regions} and {@code doors} added after its stored tags (stored first, order
     * kept, no duplicates); the same instance when nothing is new. Stored tags are never dropped: a
     * region deleted since the build keeps its tag, which only costs a domain lookup that finds nothing.
     */
    public static RoadEdge withExtraTags(RoadEdge edge, Collection<String> regions, Collection<Integer> doors) {
        Set<String> mergedRegions = new LinkedHashSet<>(edge.regionIds());
        mergedRegions.addAll(regions);
        Set<Integer> mergedDoors = new LinkedHashSet<>(edge.gateDoorIds());
        mergedDoors.addAll(doors);
        if (mergedRegions.size() == edge.regionIds().size() && mergedDoors.size() == edge.gateDoorIds().size()) {
            return edge;
        }
        return new RoadEdge(edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.geometry(), edge.length(), edge.avgWidth(),
            edge.profileId(), edge.streetId(), edge.costMultiplier(), edge.flags(), List.copyOf(mergedDoors),
            edge.domainIds(), List.copyOf(mergedRegions), edge.source(), edge.stale(), edge.confirmed());
    }
}
