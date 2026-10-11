package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spatial index over the polyline segments of a network: x/z buckets of {@code bucketSize} blocks
 * (32 by default, plan 2d) → the segments whose x/z bounding box touches the bucket. Height is not
 * bucketed (a bridge and the road below share a bucket; the snapper's weighted distance tells them
 * apart), which keeps the index tiny even for stacked streets.
 *
 * <p>Immutable after construction; queries allocate nothing but the visitor call. A segment that
 * spans several buckets is visited once per bucket the query touches - visitors must be idempotent
 * (taking a minimum is).
 */
public final class SegmentIndex {

    /** Default bucket side in blocks (plan 2d: "32×32 x/z buckets"). */
    public static final int DEFAULT_BUCKET_SIZE = 32;

    /** Callback for {@link #forEachNear}: an edge (by index in the snapshot's edge list) and one of its segments. */
    @FunctionalInterface
    public interface SegmentVisitor {
        void visit(int edgeIndex, int segmentIndex);
    }

    private final int bucketSize;
    private final Map<Long, long[]> buckets;
    private final int segmentCount;

    /**
     * @param polylines the snapshot's decoded edges, in edge-index order
     */
    SegmentIndex(List<EdgePolyline> polylines, int bucketSize) {
        if (bucketSize < 1) {
            throw new IllegalArgumentException("bucketSize must be >= 1");
        }
        this.bucketSize = bucketSize;
        Map<Long, List<Long>> building = new HashMap<>();
        int count = 0;
        for (int e = 0; e < polylines.size(); e++) {
            List<int[]> pts = polylines.get(e).points();
            for (int s = 0; s + 1 < pts.size(); s++) {
                int[] a = pts.get(s);
                int[] b = pts.get(s + 1);
                long ref = ((long) e << 32) | (s & 0xffffffffL);
                int bx0 = Math.floorDiv(Math.min(a[0], b[0]), bucketSize);
                int bx1 = Math.floorDiv(Math.max(a[0], b[0]), bucketSize);
                int bz0 = Math.floorDiv(Math.min(a[2], b[2]), bucketSize);
                int bz1 = Math.floorDiv(Math.max(a[2], b[2]), bucketSize);
                for (int bx = bx0; bx <= bx1; bx++) {
                    for (int bz = bz0; bz <= bz1; bz++) {
                        building.computeIfAbsent(key(bx, bz), k -> new ArrayList<>()).add(ref);
                    }
                }
                count++;
            }
        }
        Map<Long, long[]> compact = new HashMap<>(building.size() * 2);
        for (Map.Entry<Long, List<Long>> entry : building.entrySet()) {
            List<Long> refs = entry.getValue();
            long[] arr = new long[refs.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = refs.get(i);
            }
            compact.put(entry.getKey(), arr);
        }
        this.buckets = compact;
        this.segmentCount = count;
    }

    public int bucketSize() {
        return bucketSize;
    }

    /** Number of segments indexed (each counted once). */
    public int segmentCount() {
        return segmentCount;
    }

    /** Number of non-empty buckets. */
    public int bucketCount() {
        return buckets.size();
    }

    /**
     * Visits every segment whose x/z bounding box touches a bucket within {@code radius} blocks of
     * {@code (x, z)}. A superset of the segments within that distance; callers measure exactly.
     */
    public void forEachNear(double x, double z, double radius, SegmentVisitor visitor) {
        int bx0 = Math.floorDiv((int) Math.floor(x - radius), bucketSize);
        int bx1 = Math.floorDiv((int) Math.floor(x + radius), bucketSize);
        int bz0 = Math.floorDiv((int) Math.floor(z - radius), bucketSize);
        int bz1 = Math.floorDiv((int) Math.floor(z + radius), bucketSize);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                long[] refs = buckets.get(key(bx, bz));
                if (refs == null) {
                    continue;
                }
                for (long ref : refs) {
                    visitor.visit((int) (ref >>> 32), (int) ref);
                }
            }
        }
    }

    static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xffffffffL);
    }
}
