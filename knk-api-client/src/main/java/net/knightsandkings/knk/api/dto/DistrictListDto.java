package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DistrictListDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("townId") Integer townId,
        @JsonProperty("townName") String townName,
        @JsonProperty("worldName") String worldName
) {

    public DistrictListDto(Integer id, String name, String description, String wgRegionId, Integer townId, String townName) {
        this(id, name, description, wgRegionId, townId, townName, null);
    }
}
