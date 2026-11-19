package net.knightsandkings.knk.core.territory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the chunk cache is built from, as {@code GET api/Territory/inputs} returns it (knk-web-api
 * {@code TerritoryInputsDto}, PandiO/knk-workspace#326 "Contract for #689"): the regions territory is measured from with
 * their holder, rank and computed range, the admins' awards, and the holders' names.
 *
 * @param productionEnabled the API's {@code Production:Enabled}: the report is only sent while it is on
 * @param version           a hash of the payload: equal versions mean equal inputs
 * @param townNames         town id → name
 * @param kingdomNames      kingdom id → name
 * @param sources           every district and crown land with a region
 * @param awards            areas awarded to one holder (by conflict)
 */
public record TerritoryInputs(boolean productionEnabled, String version, Map<Integer, String> townNames,
                              Map<Integer, String> kingdomNames, List<Source> sources, List<Award> awards) {

    public static final TerritoryInputs EMPTY = new TerritoryInputs(false, "", Map.of(), Map.of(), List.of(), List.of());

    public TerritoryInputs {
        version = version == null ? "" : version;
        townNames = Map.copyOf(townNames);
        kingdomNames = Map.copyOf(kingdomNames);
        sources = List.copyOf(sources);
        awards = List.copyOf(awards);
    }

    /** What a source region is: a town's district or a kingdom's crown land. */
    public enum SourceType {
        DISTRICT,
        CROWN_LAND
    }

    /**
     * A region territory is measured from (its main region and its sub-regions).
     *
     * @param world   the region's world; null when the API doesn't know it yet (the first loaded world that has it)
     * @param range   blocks from the nearest region edge, already computed by the API (G11)
     * @param capital districts only
     */
    public record Source(SourceType type, int id, String name, String world, String regionId, List<String> subRegionIds,
                         TerritoryHolder holder, boolean capital, double range) {
        public Source {
            subRegionIds = subRegionIds == null ? List.of() : List.copyOf(subRegionIds);
        }

        /** Every region id of the source, main first. */
        public List<String> regionIds() {
            if (subRegionIds.isEmpty()) {
                return List.of(regionId);
            }
            java.util.ArrayList<String> all = new java.util.ArrayList<>(subRegionIds.size() + 1);
            all.add(regionId);
            all.addAll(subRegionIds);
            return all;
        }

        public String label() {
            return (type == SourceType.DISTRICT ? "District#" : "CrownLand#") + id + " '" + name + "'";
        }
    }

    /** An admin's award: these chunks of {@code world} belong to {@code holder} where it is one of the contesting ones. */
    public record Award(int conflictId, String world, TerritoryHolder holder, Set<Long> chunks) {
        public Award {
            chunks = Set.copyOf(chunks);
        }
    }

    /** The holder's display name, else its kind and id. */
    public String nameOf(TerritoryHolder holder) {
        if (holder == null) {
            return "";
        }
        String name = holder.kind() == TerritoryHolder.Kind.TOWN ? townNames.get(holder.id()) : kingdomNames.get(holder.id());
        return name != null && !name.isBlank() ? name : holder.toString();
    }

    /** The town of a district, from the district sources (null when the district has no region or is unknown). */
    public Integer townOfDistrict(int districtId) {
        for (Source source : sources) {
            if (source.type() == SourceType.DISTRICT && source.id() == districtId
                    && source.holder().kind() == TerritoryHolder.Kind.TOWN) {
                return source.holder().id();
            }
        }
        return null;
    }
}
