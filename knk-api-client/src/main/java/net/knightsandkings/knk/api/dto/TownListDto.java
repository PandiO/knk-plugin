package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TownListDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("worldName") String worldName
) {

    public TownListDto(Integer id, String name, String description, String wgRegionId) {
        this(id, name, description, wgRegionId, null);
    }
}
