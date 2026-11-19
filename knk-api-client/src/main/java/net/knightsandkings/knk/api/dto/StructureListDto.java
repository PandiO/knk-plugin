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
        @JsonProperty("worldName") String worldName,
        // P2 (PandiO/knk-workspace#91, #677): the effective town (district's town, or a rural structure's own town)
        @JsonProperty("townId") Integer townId
) {}
