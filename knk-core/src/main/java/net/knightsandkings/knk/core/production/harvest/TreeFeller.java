package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;

/**
 * Finds the whole tree a harvested log belongs to (PRODUCTION_KINDS § 7, P-D8: a felled tree is restored by snapshot
 * with a sapling in between): the logs connected to it (26-connected, inside the area) and the natural leaves within six
 * steps of them, the way the survey counts a tree (L12). More than {@code maxLogs} logs is not a tree (a log building or
 * a giant): then only the broken log is harvested. Bukkit-free; the block source reads the live world.
 */
public final class TreeFeller {

    /** Port: block data at a position (null outside the loaded world). */
    @FunctionalInterface
    public interface Blocks {
        String at(int x, int y, int z);
    }

    /** Port: whether a position is inside the harvest area. */
    @FunctionalInterface
    public interface Inside {
        boolean contains(int x, int y, int z);
    }

    public record Pos(int x, int y, int z, String blockData) {
    }

    /**
     * @param base    the lowest log (where the sapling goes)
     * @param species the majority log's species ({@code OAK}, {@code DARK_OAK}, {@code CRIMSON}), for the sapling
     */
    public record Tree(List<Pos> logs, List<Pos> leaves, Pos base, String species) {
        public int size() {
            return logs.size() + leaves.size();
        }
    }

    private static final int LEAF_STEPS = 6;

    private TreeFeller() {
    }

    public static boolean isLog(String material) {
        return material != null && (material.endsWith("_LOG") || material.endsWith("_WOOD") || material.endsWith("_STEM")
                || material.endsWith("_HYPHAE")) && !material.startsWith("STRIPPED_");
    }

    public static boolean isNaturalLeaves(String blockData) {
        String material = BlockStates.material(blockData);
        return material != null && (material.endsWith("_LEAVES") || material.equals("NETHER_WART_BLOCK")
                || material.equals("WARPED_WART_BLOCK") || material.equals("SHROOMLIGHT"))
                && (blockData == null || !blockData.contains("persistent=true"));
    }

    /** The tree of the log at (x, y, z), or null when it is not a tree (no log there, or more than {@code maxLogs}). */
    public static Tree find(Blocks blocks, Inside inside, int x, int y, int z, int maxLogs, int maxLeaves) {
        String start = blocks.at(x, y, z);
        if (!isLog(BlockStates.material(start))) {
            return null;
        }
        Map<Long, Pos> logs = new HashMap<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        Pos first = new Pos(x, y, z, start);
        logs.put(PlaceholderOverlay.key(x, y, z), first);
        queue.add(first);
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int nx = p.x() + dx;
                        int ny = p.y() + dy;
                        int nz = p.z() + dz;
                        long key = PlaceholderOverlay.key(nx, ny, nz);
                        if (logs.containsKey(key) || !inside.contains(nx, ny, nz)) {
                            continue;
                        }
                        String data = blocks.at(nx, ny, nz);
                        if (isLog(BlockStates.material(data))) {
                            if (logs.size() >= maxLogs) {
                                return null;
                            }
                            Pos n = new Pos(nx, ny, nz, data);
                            logs.put(key, n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        if (logs.size() > 1 || hasLeavesNear(blocks, first)) {
            return new Tree(List.copyOf(logs.values()), leaves(blocks, inside, logs, maxLeaves), base(logs.values()),
                    species(logs.values()));
        }
        return null; // a lone log without leaves: a placed block, not a tree
    }

    private static boolean hasLeavesNear(Blocks blocks, Pos log) {
        int[][] around = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            if (isNaturalLeaves(blocks.at(log.x() + d[0], log.y() + d[1], log.z() + d[2]))) {
                return true;
            }
        }
        return false;
    }

    private static List<Pos> leaves(Blocks blocks, Inside inside, Map<Long, Pos> logs, int maxLeaves) {
        Map<Long, Pos> leaves = new HashMap<>();
        Set<Long> seen = new HashSet<>(logs.keySet());
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (Pos log : logs.values()) {
            queue.add(new int[] {log.x(), log.y(), log.z(), 0});
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty() && leaves.size() < maxLeaves) {
            int[] p = queue.poll();
            if (p[3] >= LEAF_STEPS) {
                continue;
            }
            for (int[] f : faces) {
                int nx = p[0] + f[0];
                int ny = p[1] + f[1];
                int nz = p[2] + f[2];
                long key = PlaceholderOverlay.key(nx, ny, nz);
                if (!seen.add(key) || !inside.contains(nx, ny, nz)) {
                    continue;
                }
                String data = blocks.at(nx, ny, nz);
                if (isNaturalLeaves(data)) {
                    leaves.put(key, new Pos(nx, ny, nz, data));
                    queue.add(new int[] {nx, ny, nz, p[3] + 1});
                    if (leaves.size() >= maxLeaves) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(leaves.values());
    }

    private static Pos base(java.util.Collection<Pos> logs) {
        return logs.stream().min(Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z))
                .orElseThrow();
    }

    /** {@code DARK_OAK_LOG} → {@code DARK_OAK}; the most common species wins. */
    static String species(java.util.Collection<Pos> logs) {
        Map<String, Integer> counts = new HashMap<>();
        for (Pos p : logs) {
            counts.merge(speciesOf(BlockStates.material(p.blockData())), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse("OAK");
    }

    static String speciesOf(String material) {
        for (String suffix : new String[] {"_LOG", "_WOOD", "_STEM", "_HYPHAE"}) {
            if (material.endsWith(suffix)) {
                return material.substring(0, material.length() - suffix.length());
            }
        }
        return material;
    }

    /** The sapling material for a species: {@code OAK_SAPLING}, {@code MANGROVE_PROPAGULE}, {@code CRIMSON_FUNGUS}. */
    public static String saplingOf(String species) {
        return switch (species) {
            case "MANGROVE" -> "MANGROVE_PROPAGULE";
            case "CRIMSON" -> "CRIMSON_FUNGUS";
            case "WARPED" -> "WARPED_FUNGUS";
            default -> species + "_SAPLING";
        };
    }

    /** Every position of the tree, lowest first (for logs and tests). */
    public static List<Pos> all(Tree tree) {
        List<Pos> all = new ArrayList<>(tree.logs());
        all.addAll(tree.leaves());
        all.sort(Comparator.comparingInt(Pos::y));
        return all;
    }
}
