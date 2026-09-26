package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire DTOs for knk-web-api's ClansController / BannerDesignsController (Siege Phase 1).
 * Mirrors Dtos/ClanDtos.cs; colours arrive as org.bukkit.DyeColor-style names.
 */
public final class ClanDtos {
    private ClanDtos() {}

    public record BannerLayerDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("bannerDesignId") Integer bannerDesignId,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("patternKey") String patternKey,
            @JsonProperty("color") String color
    ) {}

    public record BannerDesignDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("baseColor") String baseColor,
            @JsonProperty("layers") List<BannerLayerDto> layers
    ) {}

    public record ClanDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("isNpc") Boolean isNpc,
            @JsonProperty("chatColor") String chatColor,
            @JsonProperty("bannerDesignId") Integer bannerDesignId,
            @JsonProperty("bannerDesign") BannerDesignDto bannerDesign,
            @JsonProperty("defaultForTownId") Integer defaultForTownId,
            @JsonProperty("defaultForTownName") String defaultForTownName
    ) {}
}
