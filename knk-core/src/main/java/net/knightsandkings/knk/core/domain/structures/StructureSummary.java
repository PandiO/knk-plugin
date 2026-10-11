package net.knightsandkings.knk.core.domain.structures;

/**
 * Lightweight structure projection returned by search endpoints.
 *
 * @param worldName KNG-112: the structure's world (null: not known yet)
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
    String worldName
) {

    public StructureSummary(Integer id, String name, String description, String wgRegionId, Integer houseNumber,
                            Integer streetId, String streetName, Integer districtId, String districtName) {
        this(id, name, description, wgRegionId, houseNumber, streetId, streetName, districtId, districtName, null);
    }
}
