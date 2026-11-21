package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.knightsandkings.knk.core.production.survey.AreaShape;

/**
 * The catalogue's areas by chunk, so a block break asks only the areas whose bounds touch its chunk (PandiO/knk-workspace
 * #134). Built off the event path whenever the catalogue changes; immutable afterwards, so any thread may read it.
 */
public final class HarvestAreaIndex {

    /** An area and its resolved volume. */
    public record Entry(HarvestArea area, AreaShape shape) {
    }

    public static final HarvestAreaIndex EMPTY = new HarvestAreaIndex(Map.of(), List.of(), List.of());

    private final Map<String, Map<Long, List<Entry>>> byChunk;
    private final List<Entry> entries;
    private final List<String> skipped;

    private HarvestAreaIndex(Map<String, Map<Long, List<Entry>>> byChunk, List<Entry> entries, List<String> skipped) {
        this.byChunk = byChunk;
        this.entries = entries;
        this.skipped = skipped;
    }

    /**
     * @param shapeOf       the area's volume; throws {@link IllegalArgumentException} with the reason when it has none
     * @param maxAreaChunks an area spanning more chunks is left out (logged in {@link #skipped})
     */
    public static HarvestAreaIndex build(List<HarvestArea> areas, Function<HarvestArea, AreaShape> shapeOf, int maxAreaChunks) {
        Map<String, Map<Long, List<Entry>>> byChunk = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<HarvestArea> sorted = new ArrayList<>(areas);
        sorted.sort(Comparator.comparingInt(HarvestArea::areaId));
        for (HarvestArea area : sorted) {
            AreaShape shape;
            try {
                shape = shapeOf.apply(area);
            } catch (IllegalArgumentException e) {
                skipped.add(area.spec().label() + ": " + e.getMessage());
                continue;
            }
            if (shape == null) {
                skipped.add(area.spec().label() + ": no volume");
                continue;
            }
            if (shape.chunkCount() > maxAreaChunks) {
                skipped.add(area.spec().label() + ": spans " + shape.chunkCount() + " chunks (max " + maxAreaChunks + ")");
                continue;
            }
            String world = area.spec().worldName() == null ? "" : area.spec().worldName();
            Entry entry = new Entry(area, shape);
            entries.add(entry);
            Map<Long, List<Entry>> chunks = byChunk.computeIfAbsent(world, w -> new HashMap<>());
            for (int cx = shape.minX() >> 4; cx <= shape.maxX() >> 4; cx++) {
                for (int cz = shape.minZ() >> 4; cz <= shape.maxZ() >> 4; cz++) {
                    chunks.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        return new HarvestAreaIndex(byChunk, List.copyOf(entries), List.copyOf(skipped));
    }

    /** The areas holding (x, y, z) of {@code world}, lowest area id first. */
    public List<Entry> at(String world, int x, int y, int z) {
        Map<Long, List<Entry>> chunks = byChunk.get(world);
        if (chunks == null) {
            return List.of();
        }
        List<Entry> candidates = chunks.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) {
            return List.of();
        }
        List<Entry> hits = new ArrayList<>(1);
        for (Entry entry : candidates) {
            if (entry.shape().contains(x, y, z)) {
                hits.add(entry);
            }
        }
        return hits;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Areas left out, with the reason. */
    public List<String> skipped() {
        return skipped;
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
