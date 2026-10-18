package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StructureListDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("houseNumber") Integer houseNumber,
        @JsonProperty("streetId") Integer streetId,
        @JsonProperty("streetName") String streetName,
        @JsonProperty("districtId") Integer districtId,
        @JsonProperty("districtName") String districtName,
        @JsonProperty("worldName") String worldName
) {}
