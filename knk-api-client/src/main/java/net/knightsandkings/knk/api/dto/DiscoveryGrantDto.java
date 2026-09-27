package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoveryGrantDto: one newly discovered domain. */
public record DiscoveryGrantDto(
    @JsonProperty("domainId") int domainId,
    @JsonProperty("wgRegionId") String wgRegionId,
    @JsonProperty("name") String name,
    @JsonProperty("domainType") String domainType,
    @JsonProperty("parentName") String parentName,
    @JsonProperty("source") String source,
    @JsonProperty("coins") int coins,
    @JsonProperty("gems") int gems,
    @JsonProperty("exp") int exp,
    @JsonProperty("coinsBase") int coinsBase,
    @JsonProperty("gemsBase") int gemsBase,
    @JsonProperty("expBase") int expBase
) {}
