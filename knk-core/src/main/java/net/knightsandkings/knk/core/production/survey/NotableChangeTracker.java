package net.knightsandkings.knk.core.production.survey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "After notable change" (P-D2, A10; PandiO/knk-workspace#679): counts block changes (breaks and places) inside the
 * known production areas and says when an area passed {@code threshold} changes since its last survey, at most once per
 * {@code cooldownMillis}. Areas are indexed by chunk, so a block event costs a map read. Main thread only.
 */
public final class NotableChangeTracker {

    /** A known area with its resolved shape. */
    public record TrackedArea(int areaId, int structureId, String world, AreaShape shape) {
    }

    private final int threshold;
    private final long cooldownMillis;
    private final Map<String, Map<Long, List<TrackedArea>>> byChunk = new HashMap<>();
    private final Map<Integer, Integer> changes = new HashMap<>();
    private final Map<Integer, Long> lastFired = new HashMap<>();
    private int areaCount;

    /** @param threshold changed blocks that make a change notable; 0 or less turns the tracker off */
    public NotableChangeTracker(int threshold, long cooldownMillis) {
        this.threshold = threshold;
        this.cooldownMillis = Math.max(0, cooldownMillis);
    }

    public boolean enabled() {
        return threshold > 0;
    }

    public int areaCount() {
        return areaCount;
    }

    /** Replaces the known areas; counts of areas still known are kept. Areas over {@code maxChunks} chunks are left out. */
    public void setAreas(List<TrackedArea> areas, long maxChunks) {
        byChunk.clear();
        areaCount = 0;
        List<Integer> known = new ArrayList<>();
        for (TrackedArea area : areas) {
            if (area == null || area.world() == null || area.shape().chunkCount() > maxChunks) {
                continue;
            }
            areaCount++;
            known.add(area.areaId());
            Map<Long, List<TrackedArea>> chunks = byChunk.computeIfAbsent(area.world(), w -> new HashMap<>());
            AreaShape shape = area.shape();
            for (int cx = shape.minX() >> 4; cx <= shape.maxX() >> 4; cx++) {
                for (int cz = shape.minZ() >> 4; cz <= shape.maxZ() >> 4; cz++) {
                    chunks.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(area);
                }
            }
        }
        changes.keySet().retainAll(known);
    }

    /** The areas containing the block. */
    public List<TrackedArea> areasAt(String world, int x, int y, int z) {
        Map<Long, List<TrackedArea>> chunks = byChunk.get(world);
        List<TrackedArea> candidates = chunks == null ? null : chunks.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) {
            return List.of();
        }
        List<TrackedArea> inside = new ArrayList<>(1);
        for (TrackedArea area : candidates) {
            if (area.shape().contains(x, y, z)) {
                inside.add(area);
            }
        }
        return inside;
    }

    /**
     * Records one changed block. Returns the areas that became notable with this change (their count is reset);
     * usually empty.
     */
    public List<TrackedArea> record(String world, int x, int y, int z, long nowMillis) {
        if (!enabled()) {
            return List.of();
        }
        List<TrackedArea> notable = List.of();
        for (TrackedArea area : areasAt(world, x, y, z)) {
            int count = changes.merge(area.areaId(), 1, Integer::sum);
            if (count < threshold) {
                continue;
            }
            Long last = lastFired.get(area.areaId());
            if (last != null && nowMillis - last < cooldownMillis) {
                continue;
            }
            changes.remove(area.areaId());
            lastFired.put(area.areaId(), nowMillis);
            if (notable.isEmpty()) {
                notable = new ArrayList<>();
            }
            notable.add(area);
        }
        return notable;
    }

    /** A survey of the area was reported: its count starts again. */
    public void surveyed(int areaId) {
        changes.remove(areaId);
    }

    public int changesOf(int areaId) {
        return changes.getOrDefault(areaId, 0);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return "NotableChangeTracker[" + Objects.toString(areaCount) + " areas, threshold " + threshold + "]";
    }
}
