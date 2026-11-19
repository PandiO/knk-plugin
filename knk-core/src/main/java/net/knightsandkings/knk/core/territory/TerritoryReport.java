package net.knightsandkings.knk.core.territory;

import java.util.List;

/**
 * The game server's territory report for one world ({@code PUT api/Territory/report}, knk-web-api
 * {@code TerritoryReportDto}): a full snapshot of the equal-rank overlaps and of the structures whose land is contested or
 * outside their town's territory. The API turns overlaps into conflicts and flags the structures (territory DESIGN § 3-4).
 */
public record TerritoryReport(String world, String inputsVersion, List<Overlap> overlaps, List<StructureFlag> structures) {

    public TerritoryReport {
        overlaps = List.copyOf(overlaps);
        structures = List.copyOf(structures);
    }

    /** Two holders of equal rank whose territories overlap in {@code chunks} ({@code [x, z]} chunk coordinates). */
    public record Overlap(TerritoryHolder holderA, TerritoryHolder holderB, List<int[]> chunks) {
        public Overlap {
            chunks = List.copyOf(chunks);
        }
    }

    /** {@code OutsideTerritory} or {@code Contested}; for Contested the pair of the overlap the structure stands in. */
    public record StructureFlag(int structureId, String flag, TerritoryHolder holderA, TerritoryHolder holderB) {
        public static final String OUTSIDE = "OutsideTerritory";
        public static final String CONTESTED = "Contested";
    }

    /** The API's answer (knk-web-api {@code TerritoryReportResultDto}). */
    public record Result(String world, int conflictsCreated, int conflictsUpdated, int conflictsNoLongerOverlapping,
                         int structuresFlagged, int structuresCleared, List<String> skipped) {
        public Result {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }

        public String summary() {
            return "conflicts created " + conflictsCreated + ", updated " + conflictsUpdated + ", no longer overlapping "
                    + conflictsNoLongerOverlapping + "; structures flagged " + structuresFlagged + ", cleared "
                    + structuresCleared + (skipped.isEmpty() ? "" : "; skipped " + skipped.size());
        }
    }
}
