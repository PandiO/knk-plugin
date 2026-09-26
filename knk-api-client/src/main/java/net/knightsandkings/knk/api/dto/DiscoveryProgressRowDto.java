package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/** Maps to knk-web-api's DiscoveryProgressRowDto. */
public record DiscoveryProgressRowDto(
    @JsonProperty("domainId") int domainId,
    @JsonProperty("name") String name,
    @JsonProperty("domainType") String domainType,
    @JsonProperty("parentName") String parentName,
    @JsonProperty("discovered") boolean discovered,
    @JsonProperty("discoveredAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime discoveredAt,
    @JsonProperty("coins") int coins,
    @JsonProperty("gems") int gems,
    @JsonProperty("exp") int exp
) {}
