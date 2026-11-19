package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Map outlines straight from the chunk cache (territory DESIGN § 1 "BlueMap outlines are drawn straight from the chunk
 * cache"; overview map O15: the plugin reports, the API stores, the BlueMap bridge draws). Each area (one owner, or one
 * contested set) becomes rings of block-corner points: outer rings clockwise with x east and z south, holes the other way.
 * Whole chunks contribute 16-block edges, split chunks one edge per block column, so a diagonal border keeps its steps.
 * Bukkit-free; runs off the main thread.
 */
public final class TerritoryOutlines {

    /** One closed ring of block-corner points {@code [x, z]} (the first point is not repeated at the end). */
    public record Ring(List<int[]> points, boolean hole) {
        public Ring {
            points = List.copyOf(points);
        }
    }

    /**
     * One area's outline: {@code owner} for owned land, else {@code contested} (two or more holders, nobody owns it).
     */
    public record Outline(String world, TerritoryHolder owner, List<TerritoryHolder> contested, List<Ring> rings) {
        public Outline {
            contested = List.copyOf(contested);
            rings = List.copyOf(rings);
        }

        public int pointCount() {
            return rings.stream().mapToInt(ring -> ring.points().size()).sum();
        }
    }

    private TerritoryOutlines() {
    }

    /** Every area of every world of the cache. */
    public static List<Outline> build(TerritoryCache cache) {
        List<Outline> all = new ArrayList<>();
        for (TerritoryMap map : cache.worlds().values()) {
            all.addAll(build(map));
        }
        return all;
    }

    /** Every area of one world. */
    public static List<Outline> build(TerritoryMap map) {
        // 1. One feature per area: palette indexes with the same owner (or the same contested set) share it.
        List<TerritoryVerdict> palette = map.palette();
        int[] featureOf = new int[palette.size()];
        Map<Object, Integer> features = new LinkedHashMap<>();
        List<TerritoryVerdict> featureVerdicts = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            TerritoryVerdict verdict = palette.get(i);
            if (verdict.isWilderness()) {
                featureOf[i] = -1;
                continue;
            }
            Object area = verdict.area();
            Integer feature = features.get(area);
            if (feature == null) {
                feature = featureVerdicts.size();
                features.put(area, feature);
                featureVerdicts.add(verdict);
            }
            featureOf[i] = feature;
        }
        // 2. Boundary edges per feature, interior on the right.
        List<List<int[]>> edges = new ArrayList<>();
        for (int i = 0; i < featureVerdicts.size(); i++) {
            edges.add(new ArrayList<>());
        }
        Set<Long> chunks = new LinkedHashSet<>(map.wholeChunks());
        chunks.addAll(map.splitChunks());
        for (Long key : chunks) {
            int cx = TerritoryMap.chunkX(key);
            int cz = TerritoryMap.chunkZ(key);
            int wholeIndex = map.wholeIndex(key);
            if (wholeIndex >= 0) {
                wholeChunkEdges(map, featureOf, cx, cz, featureOf[wholeIndex], edges);
            } else {
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        int x = (cx << 4) + dx;
                        int z = (cz << 4) + dz;
                        int feature = featureOf[map.indexAt(x, z)];
                        if (feature >= 0) {
                            columnEdges(map, featureOf, x, z, feature, edges.get(feature));
                        }
                    }
                }
            }
        }
        // 3. Rings per feature.
        List<Outline> outlines = new ArrayList<>();
        for (int feature = 0; feature < featureVerdicts.size(); feature++) {
            List<Ring> rings = rings(edges.get(feature));
            if (rings.isEmpty()) {
                continue;
            }
            TerritoryVerdict verdict = featureVerdicts.get(feature);
            outlines.add(new Outline(map.world(), verdict.owner(),
                    verdict.owner() == null ? verdict.contesting() : List.of(), rings));
        }
        return outlines;
    }

    private static int featureAt(TerritoryMap map, int[] featureOf, int x, int z) {
        return featureOf[map.indexAt(x, z)];
    }

    private static void wholeChunkEdges(TerritoryMap map, int[] featureOf, int cx, int cz, int feature,
                                        List<List<int[]>> edges) {
        if (feature < 0) {
            return;
        }
        List<int[]> out = edges.get(feature);
        int x0 = cx << 4;
        int z0 = cz << 4;
        int x1 = x0 + 16;
        int z1 = z0 + 16;
        // north (z0 - 1), east (x1), south (z1), west (x0 - 1): whole neighbours give one long edge, split ones per column
        side(map, featureOf, cx, cz - 1, feature, out, new int[] { x0, z0, x1, z0 }, 1, 0, x0, z0 - 1);
        side(map, featureOf, cx + 1, cz, feature, out, new int[] { x1, z0, x1, z1 }, 0, 1, x1, z0);
        side(map, featureOf, cx, cz + 1, feature, out, new int[] { x1, z1, x0, z1 }, -1, 0, x1 - 1, z1);
        side(map, featureOf, cx - 1, cz, feature, out, new int[] { x0, z1, x0, z0 }, 0, -1, x0 - 1, z1 - 1);
    }

    /**
     * One side of a whole chunk: {@code longEdge} when the neighbour chunk is whole (or wilderness) with another feature,
     * else unit edges for the neighbour columns that differ, walking from ({@code nx}, {@code nz}) by ({@code stepX},
     * {@code stepZ}).
     */
    private static void side(TerritoryMap map, int[] featureOf, int ncx, int ncz, int feature, List<int[]> out,
                             int[] longEdge, int stepX, int stepZ, int nx, int nz) {
        long neighbour = TerritoryMap.chunkKey(ncx, ncz);
        int neighbourIndex = map.wholeIndex(neighbour);
        if (neighbourIndex >= 0) {
            if (featureOf[neighbourIndex] != feature) {
                out.add(longEdge);
            }
            return;
        }
        int ex = longEdge[0];
        int ez = longEdge[1];
        for (int i = 0; i < 16; i++) {
            int x = nx + stepX * i;
            int z = nz + stepZ * i;
            if (featureAt(map, featureOf, x, z) != feature) {
                out.add(new int[] { ex, ez, ex + stepX, ez + stepZ });
            }
            ex += stepX;
            ez += stepZ;
        }
    }

    private static void columnEdges(TerritoryMap map, int[] featureOf, int x, int z, int feature, List<int[]> out) {
        if (featureAt(map, featureOf, x, z - 1) != feature) {
            out.add(new int[] { x, z, x + 1, z });
        }
        if (featureAt(map, featureOf, x + 1, z) != feature) {
            out.add(new int[] { x + 1, z, x + 1, z + 1 });
        }
        if (featureAt(map, featureOf, x, z + 1) != feature) {
            out.add(new int[] { x + 1, z + 1, x, z + 1 });
        }
        if (featureAt(map, featureOf, x - 1, z) != feature) {
            out.add(new int[] { x, z + 1, x, z });
        }
    }

    /** Links directed edges into closed rings; at a vertex with two ways out, the right turn first (pinches split). */
    static List<Ring> rings(List<int[]> edges) {
        Map<Long, List<Integer>> outgoing = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            int[] edge = edges.get(i);
            outgoing.computeIfAbsent(vertex(edge[0], edge[1]), ignored -> new ArrayList<>(1)).add(i);
        }
        boolean[] used = new boolean[edges.size()];
        List<Ring> rings = new ArrayList<>();
        for (int first = 0; first < edges.size(); first++) {
            if (used[first]) {
                continue;
            }
            List<int[]> points = new ArrayList<>();
            int current = first;
            long start = vertex(edges.get(first)[0], edges.get(first)[1]);
            while (true) {
                used[current] = true;
                int[] edge = edges.get(current);
                points.add(new int[] { edge[0], edge[1] });
                long end = vertex(edge[2], edge[3]);
                if (end == start) {
                    break;
                }
                int next = pick(edges, outgoing.get(end), used, Integer.signum(edge[2] - edge[0]),
                        Integer.signum(edge[3] - edge[1]));
                if (next < 0) {
                    break; // not closed: cannot happen for edges of whole cells; dropped below
                }
                current = next;
            }
            List<int[]> simplified = simplify(points);
            if (simplified.size() >= 4) {
                rings.add(new Ring(simplified, area(simplified) < 0));
            }
        }
        return rings;
    }

    private static int pick(List<int[]> edges, List<Integer> candidates, boolean[] used, int dx, int dz) {
        if (candidates == null) {
            return -1;
        }
        int[][] preference = { { -dz, dx }, { dx, dz }, { dz, -dx } }; // right, straight, left
        for (int[] want : preference) {
            for (int candidate : candidates) {
                if (used[candidate]) {
                    continue;
                }
                int[] edge = edges.get(candidate);
                if (Integer.signum(edge[2] - edge[0]) == want[0] && Integer.signum(edge[3] - edge[1]) == want[1]) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    /** Drops points in the middle of a straight run. */
    private static List<int[]> simplify(List<int[]> points) {
        int n = points.size();
        List<int[]> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] prev = points.get((i + n - 1) % n);
            int[] point = points.get(i);
            int[] next = points.get((i + 1) % n);
            long cross = (long) (point[0] - prev[0]) * (next[1] - point[1]) - (long) (point[1] - prev[1]) * (next[0] - point[0]);
            if (cross != 0) {
                kept.add(point);
            }
        }
        return kept;
    }

    /** Twice the signed area; positive for an outer ring (clockwise with z south). */
    static long area(List<int[]> points) {
        long sum = 0;
        for (int i = 0; i < points.size(); i++) {
            int[] a = points.get(i);
            int[] b = points.get((i + 1) % points.size());
            sum += (long) a[0] * b[1] - (long) b[0] * a[1];
        }
        return sum;
    }

    private static long vertex(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
