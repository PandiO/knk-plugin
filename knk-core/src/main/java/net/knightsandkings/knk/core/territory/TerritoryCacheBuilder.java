package net.knightsandkings.knk.core.territory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link TerritoryCache} (territory DESIGN § 1, G11; PandiO/knk-workspace#689). Pure and Bukkit-free; runs off
 * the main thread.
 * <ol>
 *   <li>For each source (a district or crown land with its shape), every chunk within its range of the shape's bounding
 *       box is classified by the distance from the chunk's centre: a chunk whose farthest column is still in range is
 *       reached <b>fully</b>, one whose nearest column is out of range not at all, the rest <b>partly</b> (the distance is
 *       1-Lipschitz, so the centre distance ± half the chunk's diagonal bounds every column).</li>
 *   <li>Each chunk gets the verdict of {@link TerritoryRules#resolve} over the holders reaching it, with the award that
 *       covers it. A chunk with a partly reaching source is resolved per block column; if every column has the same
 *       verdict it is stored whole, else <b>split</b>.</li>
 * </ol>
 */
public final class TerritoryCacheBuilder {

    /** Half the diagonal of a chunk's 16 × 16 columns, measured between column coordinates (7.5 × √2). */
    static final double HALF_DIAGONAL = 7.5 * Math.sqrt(2);

    /** {@code production.territory.max-chunks-per-world} default. */
    public static final int DEFAULT_MAX_CHUNKS_PER_WORLD = 500_000;

    /** A source and its shape in its world (the region outlines read on the main thread). */
    public record PlacedSource(TerritoryInputs.Source source, String world, TerritoryShape shape) {
    }

    private TerritoryCacheBuilder() {
    }

    /**
     * @param placed            the sources whose regions were found, with their world and shape
     * @param missing           why other sources were left out (shown as warnings)
     * @param maxChunksPerWorld a source that would take a world past this many chunks is left out (logged)
     */
    public static TerritoryCache build(TerritoryInputs inputs, List<PlacedSource> placed, List<String> missing,
                                       long geometryFingerprint, int maxChunksPerWorld) {
        long started = System.nanoTime();
        List<String> warnings = new ArrayList<>(missing);
        Map<String, List<PlacedSource>> byWorld = new LinkedHashMap<>();
        for (PlacedSource source : placed) {
            byWorld.computeIfAbsent(source.world(), ignored -> new ArrayList<>()).add(source);
        }
        Map<String, TerritoryMap> worlds = new LinkedHashMap<>();
        for (Map.Entry<String, List<PlacedSource>> entry : byWorld.entrySet()) {
            worlds.put(entry.getKey(), buildWorld(entry.getKey(), entry.getValue(), awardsOf(inputs, entry.getKey()),
                    Math.max(1, maxChunksPerWorld), warnings));
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new TerritoryCache(inputs, geometryFingerprint, worlds, Instant.now(), millis, warnings);
    }

    private static Map<Long, TerritoryHolder> awardsOf(TerritoryInputs inputs, String world) {
        Map<Long, TerritoryHolder> awards = new HashMap<>();
        for (TerritoryInputs.Award award : inputs.awards()) {
            if (award.world() != null && award.world().equals(world)) {
                for (Long chunk : award.chunks()) {
                    awards.put(chunk, award.holder());
                }
            }
        }
        return awards;
    }

    /** The work of one chunk: the holders reaching it fully and the sources reaching it partly. */
    private static final class ChunkWork {
        final List<TerritoryHolder> full = new ArrayList<>(2);
        List<PlacedSource> partial;
    }

    private static TerritoryMap buildWorld(String world, List<PlacedSource> sources, Map<Long, TerritoryHolder> awards,
                                           int maxChunks, List<String> warnings) {
        Map<Long, ChunkWork> work = new HashMap<>();
        for (PlacedSource placed : sources) {
            if (!classify(placed, work, maxChunks)) {
                warnings.add(placed.source().label() + " in " + world + ": left out, the world would pass "
                        + maxChunks + " territory chunks (production.territory.max-chunks-per-world)");
            }
        }
        List<TerritoryVerdict> palette = new ArrayList<>();
        Map<TerritoryVerdict, Integer> indexes = new HashMap<>();
        palette.add(TerritoryVerdict.WILDERNESS);
        indexes.put(TerritoryVerdict.WILDERNESS, TerritoryMap.WILDERNESS);
        Map<Long, Integer> whole = new HashMap<>();
        Map<Long, short[]> split = new HashMap<>();
        List<TerritoryHolder> claims = new ArrayList<>();
        for (Map.Entry<Long, ChunkWork> entry : work.entrySet()) {
            long key = entry.getKey();
            ChunkWork chunk = entry.getValue();
            TerritoryHolder award = awards.get(key);
            if (chunk.partial == null) {
                int index = intern(TerritoryRules.resolve(chunk.full, award), palette, indexes);
                if (index != TerritoryMap.WILDERNESS) {
                    whole.put(key, index);
                }
                continue;
            }
            int baseX = TerritoryMap.chunkX(key) << 4;
            int baseZ = TerritoryMap.chunkZ(key) << 4;
            short[] columns = new short[256];
            boolean uniform = true;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    claims.clear();
                    claims.addAll(chunk.full);
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    for (PlacedSource source : chunk.partial) {
                        if (source.shape().distance(x, z) <= source.source().range()) {
                            claims.add(source.source().holder());
                        }
                    }
                    int index = intern(TerritoryRules.resolve(claims, award), palette, indexes);
                    columns[TerritoryMap.columnIndex(x, z)] = (short) index;
                    uniform &= index == columns[0];
                }
            }
            if (uniform) {
                if (columns[0] != TerritoryMap.WILDERNESS) {
                    whole.put(key, (int) columns[0]);
                }
            } else {
                split.put(key, columns);
            }
        }
        return new TerritoryMap(world, palette, whole, split);
    }

    /** Adds the source's chunks to {@code work}; false (and nothing added) when that would pass the budget. */
    private static boolean classify(PlacedSource placed, Map<Long, ChunkWork> work, int maxChunks) {
        TerritoryShape shape = placed.shape();
        double range = Math.max(0, placed.source().range());
        int reach = (int) Math.ceil(range);
        int minCx = Math.floorDiv(shape.minX() - reach, 16);
        int maxCx = Math.floorDiv(shape.maxX() + reach, 16);
        int minCz = Math.floorDiv(shape.minZ() - reach, 16);
        int maxCz = Math.floorDiv(shape.maxZ() + reach, 16);
        List<long[]> reached = new ArrayList<>();
        int added = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                double centre = shape.distance(cx * 16 + 7.5, cz * 16 + 7.5);
                if (centre - HALF_DIAGONAL > range) {
                    continue;
                }
                long key = TerritoryMap.chunkKey(cx, cz);
                reached.add(new long[] { key, centre + HALF_DIAGONAL <= range ? 1 : 0 });
                if (!work.containsKey(key)) {
                    added++;
                }
            }
        }
        if (work.size() + added > maxChunks) {
            return false;
        }
        TerritoryHolder holder = placed.source().holder();
        for (long[] chunk : reached) {
            ChunkWork entry = work.computeIfAbsent(chunk[0], ignored -> new ChunkWork());
            if (chunk[1] == 1) {
                entry.full.add(holder);
            } else {
                if (entry.partial == null) {
                    entry.partial = new ArrayList<>(2);
                }
                entry.partial.add(placed);
            }
        }
        return true;
    }

    private static int intern(TerritoryVerdict verdict, List<TerritoryVerdict> palette,
                              Map<TerritoryVerdict, Integer> indexes) {
        Integer index = indexes.get(verdict);
        if (index != null) {
            return index;
        }
        if (palette.size() >= Short.MAX_VALUE) {
            throw new IllegalStateException("More than " + Short.MAX_VALUE + " distinct territory verdicts in one world");
        }
        palette.add(verdict);
        indexes.put(verdict, palette.size() - 1);
        return palette.size() - 1;
    }
}
