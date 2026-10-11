package net.knightsandkings.knk.core.domain.towns;

/** @param worldName KNG-112: the town's world (null: not known yet) */
public record TownSummary(Integer id, String name, String description, String wgRegionId, String worldName) {

    public TownSummary(Integer id, String name, String description, String wgRegionId) {
        this(id, name, description, wgRegionId, null);
    }
}
