package net.knightsandkings.knk.core.npc.nav.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The box a snapshot covers (link B1, PandiO/knk-workspace#670): the route line's blocks (since link C1 also the route's
 * from and to) with a margin round them, and
 * the region files ({@code r.<x>.<z>.mca}, 512 × 512 blocks each) that hold its blocks.
 *
 * @param min the lowest block (x, y, z), inclusive
 * @param max the highest block (x, y, z), inclusive
 */
public record SnapshotBox(int[] min, int[] max) {

    /** Blocks added round the line sideways: room for groups, queues and side steps. */
    public static final int MARGIN = 8;
    /** Blocks added below the line (floors, falls) and above it (stairs, roofs, heads). */
    public static final int MARGIN_BELOW = 6;
    public static final int MARGIN_ABOVE = 12;

    public SnapshotBox {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.length != 3 || max.length != 3) {
            throw new IllegalArgumentException("a box corner has three coordinates");
        }
        for (int i = 0; i < 3; i++) {
            if (min[i] > max[i]) {
                throw new IllegalArgumentException("box min above max on axis " + i);
            }
        }
        min = min.clone();
        max = max.clone();
    }

    /** The box round a line of points with the default margins; at least one point. */
    public static SnapshotBox around(List<NavPoint> line) {
        return around(line, MARGIN, MARGIN_BELOW, MARGIN_ABOVE);
    }

    /**
     * The box round a route (PandiO/knk-workspace#700, link C1): its road line and its from and to with the default
     * margins, so an off-road start or goal (a goal upstairs in a tower) and the domains round it are inside. A null
     * end is left out; an empty line takes the ends alone.
     */
    public static SnapshotBox aroundRoute(List<NavPoint> line, NavPoint from, NavPoint to) {
        List<NavPoint> points = new ArrayList<>(line == null ? List.of() : line);
        if (from != null) {
            points.add(from);
        }
        if (to != null) {
            points.add(to);
        }
        return around(points);
    }

    /** The box round a line of points with these margins; at least one point. */
    public static SnapshotBox around(List<NavPoint> line, int margin, int below, int above) {
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("a snapshot box needs at least one point");
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (NavPoint p : line) {
            int[] b = {p.blockX(), p.blockY(), p.blockZ()};
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], b[i]);
                hi[i] = Math.max(hi[i], b[i]);
            }
        }
        return new SnapshotBox(new int[] {lo[0] - margin, lo[1] - below, lo[2] - margin},
            new int[] {hi[0] + margin, hi[1] + above, hi[2] + margin});
    }

    /** Whether a block lies in the box. */
    public boolean contains(int x, int y, int z) {
        return x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
    }

    /** Whether a block column (any height) lies in the box's x and z. */
    public boolean containsColumn(int x, int z) {
        return x >= min[0] && x <= max[0] && z >= min[2] && z <= max[2];
    }

    /** Whether an axis-aligned block range (inclusive) touches the box. */
    public boolean touches(int[] lo, int[] hi) {
        for (int i = 0; i < 3; i++) {
            if (hi[i] < min[i] || lo[i] > max[i]) {
                return false;
            }
        }
        return true;
    }

    /** The region files whose 512 × 512 columns the box touches, west to east, then north to south. */
    public List<String> regionFiles() {
        List<String> out = new ArrayList<>();
        for (int rz = Math.floorDiv(min[2], 512); rz <= Math.floorDiv(max[2], 512); rz++) {
            for (int rx = Math.floorDiv(min[0], 512); rx <= Math.floorDiv(max[0], 512); rx++) {
                out.add("r." + rx + "." + rz + ".mca");
            }
        }
        return out;
    }

    /** "x y z to x y z". */
    public String describe() {
        return min[0] + " " + min[1] + " " + min[2] + " to " + max[0] + " " + max[1] + " " + max[2];
    }
}
