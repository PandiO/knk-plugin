package net.knightsandkings.knk.core.production.survey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.knightsandkings.knk.core.production.survey.SurveyProfile.UnitType;

/**
 * Counts what a production area contains (PRODUCTION_KINDS § 3.2, § 8; PandiO/knk-workspace#679). Pure: it reads a
 * {@link BlockSnapshotSource} (captured chunk snapshots), the placeholder overlay and the entities seen in the area, so
 * it runs off the main thread and gives the same units and hash for the same world (survey idempotence).
 * <ol>
 *   <li>{@code BLOCK}: rock and ores (or the profile's {@code materials}), {@code BLOCK:<MATERIAL>}, the whole volume
 *       (§ 8.3 recommendation).</li>
 *   <li>{@code CROP}: crop blocks per type, growth stage ignored (§ 8.1), {@code CROP:<TYPE>}.</li>
 *   <li>{@code FLOWER}: flowers per type, the lower half of tall ones, {@code FLOWER:<TYPE>}.</li>
 *   <li>{@code SAPLING}: {@code SAPLING:<SPECIES>} (§ 8.2: future forest).</li>
 *   <li>{@code TREE}: log clusters (26-connected logs inside the area) with leaves within reach; species by majority
 *       log, size class by log count, {@code TREE:<SPECIES>:<SMALL|MEDIUM|LARGE>}; a cluster over {@code maxLogs} is a
 *       building and not counted.</li>
 *   <li>{@code WATER}: still water source blocks per biome of the column's top water block, {@code WATER:<BIOME>}, with
 *       extra JSON {@code columns}, {@code avgDepth} and {@code openSkyShare} (§ 8.5).</li>
 *   <li>{@code ENTITY}: the animals seen in the area per type (§ 8.7), {@code ENTITY:<TYPE>}.</li>
 * </ol>
 * Shared split (G7): each unit's {@code sharedBy} is 1 + the number of other structures whose shared areas contain the
 * unit's position (a tree's lowest log, a water column's top block). Rows are one per (key, sharedBy).
 */
public final class SurveyCounter {

    /** A shared area of another structure that overlaps the surveyed one. */
    public record SharedArea(int areaId, int structureId, AreaShape shape) {
    }

    /** An entity seen in the area's chunks (captured on the main thread). */
    public record EntitySighting(String type, int x, int y, int z) {
    }

    /** What to count. {@code world} names the overlay's world. */
    public record Input(String world, int structureId, AreaShape shape, SurveyProfile profile, BlockSnapshotSource blocks,
                        PlaceholderOverlay overlay, List<EntitySighting> entities, List<SharedArea> shared) {
        public Input {
            overlay = overlay == null ? PlaceholderOverlay.NONE : overlay;
            entities = entities == null ? List.of() : List.copyOf(entities);
            shared = shared == null ? List.of() : List.copyOf(shared);
        }
    }

    /**
     * The outcome.
     *
     * @param units          the rows, sorted by key then sharedBy
     * @param contentHash    SHA-256 of the rows (hex): the same world gives the same hash
     * @param blocksRead     blocks read
     * @param missingColumns columns whose chunk was not captured (not counted)
     * @param trees          trees found
     * @param buildings      log clusters too large to be a tree
     * @param placeholders   blocks read as their original through the overlay
     * @param truncated      rows left out over {@link SurveyUnit#MAX_ROWS} (the smallest)
     */
    public record Result(List<SurveyUnit> units, String contentHash, long blocksRead, long missingColumns, int trees,
                         int buildings, long placeholders, int truncated) {
        public long totalUnits() {
            return units.stream().mapToLong(SurveyUnit::count).sum();
        }

        /** The report's note: what the survey read. */
        public String note() {
            return "blocks " + blocksRead + ", trees " + trees + (buildings > 0 ? ", log buildings skipped " + buildings : "")
                    + (placeholders > 0 ? ", placeholders " + placeholders : "")
                    + (missingColumns > 0 ? ", columns not loaded " + missingColumns : "")
                    + (truncated > 0 ? ", rows cut " + truncated : "");
        }
    }

    private SurveyCounter() {
    }

    public static Result count(Input input) {
        return new Run(input).run();
    }

    /** One count. */
    private static final class Run {
        private final Input in;
        private final SurveyProfile profile;
        private final AreaShape shape;
        private final BlockSnapshotSource blocks;
        private final boolean overlayed;
        private final Map<String, Map<Integer, long[]>> rows = new TreeMap<>();
        private final Map<Long, String> logs = new HashMap<>();
        private final Map<String, Map<Integer, long[]>> water = new TreeMap<>();
        private long blocksRead;
        private long missingColumns;
        private long placeholders;
        private int trees;
        private int buildings;

        Run(Input input) {
            this.in = input;
            this.profile = input.profile();
            this.shape = input.shape();
            this.blocks = input.blocks();
            this.overlayed = input.overlay() != PlaceholderOverlay.NONE;
        }

        Result run() {
            boolean anyBlocks = profile.counts(UnitType.BLOCK) || profile.counts(UnitType.CROP)
                    || profile.counts(UnitType.FLOWER) || profile.counts(UnitType.SAPLING) || profile.counts(UnitType.TREE)
                    || profile.counts(UnitType.WATER);
            if (anyBlocks) {
                scanBlocks();
            }
            if (profile.counts(UnitType.TREE)) {
                clusterTrees();
            }
            if (profile.counts(UnitType.ENTITY)) {
                countEntities();
            }
            return finish();
        }

        private String materialAt(BlockSnapshotSource.ChunkBlocks chunk, int x, int y, int z) {
            if (overlayed) {
                String original = in.overlay().originalAt(in.world(), x, y, z);
                if (original != null) {
                    placeholders++;
                    return original;
                }
            }
            return chunk.material(x & 15, y, z & 15);
        }

        /** Material anywhere in the captured chunks (neighbours of logs), through the overlay; null outside. */
        private String materialAnywhere(int x, int y, int z) {
            if (y < blocks.minY() || y >= blocks.maxY()) {
                return null;
            }
            if (overlayed) {
                String original = in.overlay().originalAt(in.world(), x, y, z);
                if (original != null) {
                    return original;
                }
            }
            BlockSnapshotSource.ChunkBlocks chunk = blocks.chunk(x >> 4, z >> 4);
            return chunk == null ? null : chunk.material(x & 15, y, z & 15);
        }

        private void scanBlocks() {
            boolean countBlocks = profile.counts(UnitType.BLOCK);
            boolean crops = profile.counts(UnitType.CROP);
            boolean flowers = profile.counts(UnitType.FLOWER);
            boolean saplings = profile.counts(UnitType.SAPLING);
            boolean treesOn = profile.counts(UnitType.TREE);
            boolean waterOn = profile.counts(UnitType.WATER);
            Set<String> materials = profile.materials();
            int low = Math.max(shape.minY(), blocks.minY());
            int high = Math.min(shape.maxY(), blocks.maxY() - 1);
            for (int x = shape.minX(); x <= shape.maxX(); x++) {
                for (int z = shape.minZ(); z <= shape.maxZ(); z++) {
                    if (!shape.containsColumn(x, z)) {
                        continue;
                    }
                    BlockSnapshotSource.ChunkBlocks chunk = blocks.chunk(x >> 4, z >> 4);
                    if (chunk == null) {
                        missingColumns++;
                        continue;
                    }
                    int waterBlocks = 0;
                    int topWater = Integer.MIN_VALUE;
                    String previous = null;
                    for (int y = low; y <= high; y++) {
                        String material = materialAt(chunk, x, y, z);
                        blocksRead++;
                        if (material == null || material.endsWith("AIR")) {
                            previous = material;
                            continue;
                        }
                        if (countBlocks && (materials.isEmpty() ? SurveyMaterials.isRockOrOre(material) : materials.contains(material))) {
                            add(SurveyUnit.key("BLOCK", material), x, y, z);
                        } else if (crops && SurveyMaterials.CROPS.containsKey(material)) {
                            add(SurveyUnit.key("CROP", SurveyMaterials.CROPS.get(material)), x, y, z);
                        } else if (flowers && SurveyMaterials.FLOWERS.contains(material)
                                && !(SurveyMaterials.TALL_FLOWERS.contains(material) && material.equals(previous))) {
                            add(SurveyUnit.key("FLOWER", material), x, y, z);
                        } else if (saplings && SurveyMaterials.saplingSpecies(material) != null) {
                            add(SurveyUnit.key("SAPLING", SurveyMaterials.saplingSpecies(material)), x, y, z);
                        } else if (treesOn && SurveyMaterials.logSpecies(material) != null) {
                            logs.put(PlaceholderOverlay.key(x, y, z), SurveyMaterials.logSpecies(material));
                        } else if (waterOn && material.equals("WATER")
                                && (chunk.waterSource(x & 15, y, z & 15) || overlayedWater(x, y, z))) {
                            waterBlocks++;
                            topWater = y;
                        }
                        previous = material;
                    }
                    if (waterBlocks > 0) {
                        String biome = chunk.biome(x & 15, topWater, z & 15);
                        boolean openSky = chunk.highestY(x & 15, z & 15) <= topWater;
                        long[] agg = water.computeIfAbsent(SurveyUnit.key("WATER", biome), k -> new TreeMap<>())
                                .computeIfAbsent(sharedBy(x, topWater, z), k -> new long[3]);
                        agg[0] += waterBlocks;
                        agg[1]++;
                        agg[2] += openSky ? 1 : 0;
                    }
                }
            }
        }

        private boolean overlayedWater(int x, int y, int z) {
            return overlayed && "WATER".equals(in.overlay().originalAt(in.world(), x, y, z));
        }

        private void clusterTrees() {
            Set<Long> seen = new HashSet<>();
            for (Long start : logs.keySet()) {
                if (!seen.add(start)) {
                    continue;
                }
                List<Long> cluster = new ArrayList<>();
                ArrayDeque<Long> todo = new ArrayDeque<>();
                todo.add(start);
                while (!todo.isEmpty()) {
                    long pos = todo.poll();
                    cluster.add(pos);
                    int px = x(pos), py = y(pos), pz = z(pos);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }
                                long next = PlaceholderOverlay.key(px + dx, py + dy, pz + dz);
                                if (logs.containsKey(next) && seen.add(next)) {
                                    todo.add(next);
                                }
                            }
                        }
                    }
                }
                if (cluster.size() > profile.maxLogs()) {
                    buildings++;
                    continue;
                }
                if (!hasLeaves(cluster)) {
                    continue;
                }
                trees++;
                Map<String, Integer> species = new TreeMap<>();
                long base = cluster.get(0);
                for (long pos : cluster) {
                    species.merge(logs.get(pos), 1, Integer::sum);
                    if (lower(pos, base)) {
                        base = pos;
                    }
                }
                String majority = species.entrySet().stream()
                        .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                        .map(Map.Entry::getKey).orElse("UNKNOWN");
                int size = cluster.size();
                String sizeClass = size <= profile.smallMaxLogs() ? "SMALL" : size <= profile.mediumMaxLogs() ? "MEDIUM" : "LARGE";
                add(SurveyUnit.key("TREE", majority, sizeClass), x(base), y(base), z(base));
            }
        }

        private static boolean lower(long a, long b) {
            if (y(a) != y(b)) {
                return y(a) < y(b);
            }
            if (x(a) != x(b)) {
                return x(a) < x(b);
            }
            return z(a) < z(b);
        }

        private boolean hasLeaves(List<Long> cluster) {
            int reach = profile.leafReach();
            for (long pos : cluster) {
                int px = x(pos), py = y(pos), pz = z(pos);
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dy = -reach; dy <= reach; dy++) {
                        for (int dz = -reach; dz <= reach; dz++) {
                            if (SurveyMaterials.isLeaves(materialAnywhere(px + dx, py + dy, pz + dz))) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        private void countEntities() {
            Set<String> types = profile.entityTypes().isEmpty() ? SurveyMaterials.FARM_ANIMALS : profile.entityTypes();
            for (EntitySighting entity : in.entities()) {
                String type = entity.type() == null ? null : entity.type().toUpperCase(Locale.ROOT);
                if (type != null && types.contains(type) && shape.contains(entity.x(), entity.y(), entity.z())) {
                    add(SurveyUnit.key("ENTITY", type), entity.x(), entity.y(), entity.z());
                }
            }
        }

        private void add(String key, int x, int y, int z) {
            rows.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(sharedBy(x, y, z), k -> new long[1])[0]++;
        }

        /** 1 + the distinct other structures whose shared areas contain the position (G7). */
        private int sharedBy(int x, int y, int z) {
            List<SharedArea> shared = in.shared();
            if (shared.isEmpty()) {
                return 1;
            }
            Set<Integer> structures = null;
            for (SharedArea area : shared) {
                if (area.structureId() != in.structureId() && area.shape().contains(x, y, z)) {
                    if (structures == null) {
                        structures = new HashSet<>();
                    }
                    structures.add(area.structureId());
                }
            }
            return structures == null ? 1 : 1 + structures.size();
        }

        private Result finish() {
            List<SurveyUnit> units = new ArrayList<>();
            rows.forEach((key, bySharedBy) -> bySharedBy.forEach((sharedBy, count) ->
                    units.add(new SurveyUnit(key, count[0], sharedBy, null))));
            water.forEach((key, bySharedBy) -> bySharedBy.forEach((sharedBy, agg) -> units.add(new SurveyUnit(key, agg[0],
                    sharedBy, String.format(Locale.ROOT, "{\"columns\":%d,\"avgDepth\":%.2f,\"openSkyShare\":%.2f}",
                            agg[1], (double) agg[0] / agg[1], (double) agg[2] / agg[1])))));
            int truncated = 0;
            if (units.size() > SurveyUnit.MAX_ROWS) {
                truncated = units.size() - SurveyUnit.MAX_ROWS;
                units.sort(Comparator.comparingLong(SurveyUnit::count).reversed().thenComparing(SurveyUnit::unitKey));
                units.subList(SurveyUnit.MAX_ROWS, units.size()).clear();
            }
            units.sort(Comparator.comparing(SurveyUnit::unitKey).thenComparingInt(SurveyUnit::sharedBy));
            return new Result(List.copyOf(units), hash(units), blocksRead, missingColumns, trees, buildings, placeholders,
                    truncated);
        }
    }

    /** SHA-256 (hex) of the sorted rows: {@code key|sharedBy|count|extra} per line. */
    public static String hash(List<SurveyUnit> units) {
        List<SurveyUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparing(SurveyUnit::unitKey).thenComparingInt(SurveyUnit::sharedBy));
        StringBuilder text = new StringBuilder();
        for (SurveyUnit unit : sorted) {
            text.append(unit.unitKey()).append('|').append(unit.sharedBy()).append('|').append(unit.count()).append('|')
                    .append(unit.extra() == null ? "" : unit.extra()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static int x(long key) {
        return (int) (key >> 38) << 6 >> 6;
    }

    static int z(long key) {
        return (int) ((key >> 12) & 0x3FFFFFF) << 6 >> 6;
    }

    static int y(long key) {
        return (int) (key & 0xFFF) << 20 >> 20;
    }
}
