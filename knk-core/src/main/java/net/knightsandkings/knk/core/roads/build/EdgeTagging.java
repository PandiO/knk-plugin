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
     * A sampled floor block and how far along the polyline it lies (rev. 7 Part A: the routing view cuts an
     * edge where its tags change, so it needs the position, not only the set).
     *
     * @param along polyline distance from the first point, as {@code EdgePolyline} measures it
     */
    public record Sample(double along, int x, int y, int z) {
    }

    /**
     * Floor blocks along the polyline, at most {@code step} apart, both ends included, consecutive
     * duplicates removed. Between two points the position is interpolated from block centre to block
     * centre; the floor y is rounded.
     */
    public static List<int[]> samples(List<int[]> geometry, double step) {
        List<int[]> out = new ArrayList<>();
        for (Sample s : alongSamples(geometry, step)) {
            out.add(new int[] {s.x(), s.y(), s.z()});
        }
        return out;
    }

    /** {@link #samples} with each block's position along the polyline (the first one where it was met). */
    public static List<Sample> alongSamples(List<int[]> geometry, double step) {
        Objects.requireNonNull(geometry, "geometry");
        if (!(step > 0)) {
            throw new IllegalArgumentException("step must be positive: " + step);
        }
        List<Sample> out = new ArrayList<>();
        double cumulative = 0;
        for (int i = 0; i < geometry.size(); i++) {
            int[] p = geometry.get(i);
            if (i == 0) {
                add(out, 0, p[0], p[1], p[2]);
            }
            if (i + 1 >= geometry.size()) {
                continue;
            }
            int[] q = geometry.get(i + 1);
            double dx = q[0] - p[0];
            double dy = q[1] - p[1];
            double dz = q[2] - p[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int n = Math.max(1, (int) Math.ceil(length / step));
            for (int k = 1; k <= n; k++) {
                double t = (double) k / n;
                add(out, cumulative + length * t, (int) Math.floor(p[0] + 0.5 + dx * t), (int) Math.floor(p[1] + dy * t + 0.5),
                    (int) Math.floor(p[2] + 0.5 + dz * t));
            }
            cumulative += length;
        }
        return out;
    }

    private static void add(List<Sample> out, double along, int x, int y, int z) {
        if (!out.isEmpty()) {
            Sample last = out.get(out.size() - 1);
            if (last.x() == x && last.y() == y && last.z() == z) {
                return;
            }
        }
        out.add(new Sample(along, x, y, z));
    }

    /**
     * The gate doors whose closed footprint the polyline passes, in the order it meets them: a door
     * block at the floor, the feet or the head of a sample (as the builder's span tagging).
     */
    public static List<Integer> doorsAlong(List<int[]> geometry, GateCells gates) {
        Set<Integer> doors = new LinkedHashSet<>();
        for (Set<Integer> at : doorsAt(alongSamples(geometry, DOOR_STEP), gates)) {
            doors.addAll(at);
        }
        return new ArrayList<>(doors);
    }

    /** The gate doors at each of {@code samples} (floor, feet or head; as {@link #doorsAlong}), same order. */
    public static List<Set<Integer>> doorsAt(List<Sample> samples, GateCells gates) {
        Objects.requireNonNull(gates, "gates");
        List<Set<Integer>> out = new ArrayList<>(samples.size());
        for (Sample s : samples) {
            Set<Integer> doors = new LinkedHashSet<>();
            for (int h = 0; h <= 2; h++) {
                OptionalInt door = gates.doorAt(s.x(), s.y() + h, s.z());
                if (door.isPresent()) {
                    doors.add(door.getAsInt());
                }
            }
            out.add(doors.isEmpty() ? Set.of() : doors);
        }
        return out;
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
