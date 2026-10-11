package net.knightsandkings.knk.core.domain.districts;

/** @param worldName KNG-112: the district's world (null: not known yet) */
public record DistrictSummary(Integer id, String name, String description, String wgRegionId, Integer townId, String townName,
                              String worldName) {

    public DistrictSummary(Integer id, String name, String description, String wgRegionId, Integer townId, String townName) {
        this(id, name, description, wgRegionId, townId, townName, null);
    }
}
