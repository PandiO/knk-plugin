package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the per-world {@link TerritoryReport} from the cache (territory DESIGN § 3-4, PandiO/knk-workspace#689):
 * <ol>
 *   <li>Overlaps: every chunk with a contested column (whole or split) adds its chunk to each pair of the contesting
 *       holders; only conflict pairs (equal rank, different holders) are reported.</li>
 *   <li>Structures: a structure is placed at its region's centre column. Contested land flags it {@code Contested} with
 *       the first two contesting holders; land its own town does not own (wilderness, another town, crown land) flags it
 *       {@code OutsideTerritory}. A structure whose town or position is unknown is not reported.</li>
 * </ol>
 */
public final class TerritoryReportBuilder {

    /** A structure where it stands: its id, its town (through its district) and its region's centre column. */
    public record StructurePlacement(int structureId, Integer townId, String world, int x, int z) {
    }

    private TerritoryReportBuilder() {
    }

    /** One report per world in {@code worlds} (every world of the cache plus any reported before, so old ones clear). */
    public static List<TerritoryReport> build(TerritoryCache cache, List<StructurePlacement> structures, Set<String> worlds) {
        List<TerritoryReport> reports = new ArrayList<>();
        for (String world : new TreeSet<>(worlds)) {
            reports.add(buildWorld(cache, world, structures));
        }
        return reports;
    }

    static TerritoryReport buildWorld(TerritoryCache cache, String world, List<StructurePlacement> structures) {
        TerritoryMap map = cache.worlds().get(world);
        Map<Pair, Set<Long>> overlaps = new TreeMap<>();
        if (map != null) {
            Set<Long> chunks = new LinkedHashSet<>(map.wholeChunks());
            chunks.addAll(map.splitChunks());
            for (Long key : chunks) {
                for (TerritoryVerdict verdict : map.verdictsIn(key)) {
                    if (!verdict.isContested()) {
                        continue;
                    }
                    List<TerritoryHolder> holders = verdict.contesting();
                    for (int i = 0; i < holders.size(); i++) {
                        for (int j = i + 1; j < holders.size(); j++) {
                            if (TerritoryRules.isConflictPair(holders.get(i), holders.get(j))) {
                                overlaps.computeIfAbsent(new Pair(holders.get(i), holders.get(j)), p -> new TreeSet<>(CHUNK_ORDER))
                                        .add(key);
                            }
                        }
                    }
                }
            }
        }
        List<TerritoryReport.Overlap> overlapList = new ArrayList<>();
        overlaps.forEach((pair, keys) -> {
            List<int[]> list = new ArrayList<>(keys.size());
            for (Long key : keys) {
                list.add(new int[] { TerritoryMap.chunkX(key), TerritoryMap.chunkZ(key) });
            }
            overlapList.add(new TerritoryReport.Overlap(pair.a(), pair.b(), list));
        });
        List<TerritoryReport.StructureFlag> flags = new ArrayList<>();
        for (StructurePlacement structure : structures) {
            if (structure.townId() == null || structure.world() == null || !structure.world().equals(world)) {
                continue;
            }
            TerritoryVerdict verdict = cache.lookup(world, structure.x(), structure.z());
            if (verdict.isContested()) {
                flags.add(new TerritoryReport.StructureFlag(structure.structureId(), TerritoryReport.StructureFlag.CONTESTED,
                        verdict.contesting().get(0), verdict.contesting().get(1)));
            } else if (!TerritoryHolder.town(structure.townId()).equals(verdict.owner())) {
                flags.add(new TerritoryReport.StructureFlag(structure.structureId(), TerritoryReport.StructureFlag.OUTSIDE,
                        null, null));
            }
        }
        flags.sort(Comparator.comparingInt(TerritoryReport.StructureFlag::structureId));
        return new TerritoryReport(world, cache.version(), overlapList, flags);
    }

    /** Chunks ordered by x, then z (a stable report). */
    static final Comparator<Long> CHUNK_ORDER = Comparator.<Long>comparingInt(TerritoryMap::chunkX)
            .thenComparingInt(TerritoryMap::chunkZ);

    private record Pair(TerritoryHolder a, TerritoryHolder b) implements Comparable<Pair> {
        @Override
        public int compareTo(Pair other) {
            int byA = a.compareTo(other.a);
            return byA != 0 ? byA : b.compareTo(other.b);
        }
    }
}
