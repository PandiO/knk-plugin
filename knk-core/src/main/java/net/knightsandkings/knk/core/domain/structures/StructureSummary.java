package net.knightsandkings.knk.core.domain.structures;

/**
 * Lightweight structure projection returned by search endpoints.
 *
 * @param worldName KNG-112: the structure's world (null: not known yet)
 * @param townId    P2 (PandiO/knk-workspace#91, #677): the structure's effective town, its district's town or a rural
 *                  structure's own town (null: an API from before P2, or not known)
 */
public record StructureSummary(
    Integer id,
    String name,
    String description,
    String wgRegionId,
    Integer houseNumber,
    Integer streetId,
    String streetName,
    Integer districtId,
    String districtName,
    String worldName,
    Integer townId
) {

    public StructureSummary(Integer id, String name, String description, String wgRegionId, Integer houseNumber,
                            Integer streetId, String streetName, Integer districtId, String districtName,
                            String worldName) {
        this(id, name, description, wgRegionId, houseNumber, streetId, streetName, districtId, districtName, worldName,
            null);
    }

    public StructureSummary(Integer id, String name, String description, String wgRegionId, Integer houseNumber,
                            Integer streetId, String streetName, Integer districtId, String districtName) {
        this(id, name, description, wgRegionId, houseNumber, streetId, streetName, districtId, districtName, null, null);
    }
}
