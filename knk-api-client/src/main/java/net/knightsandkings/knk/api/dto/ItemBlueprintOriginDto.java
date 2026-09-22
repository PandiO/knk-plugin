package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemBlueprintOriginDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
        @JsonProperty("domainId") Integer domainId,
        @JsonProperty("domain") DomainSummaryDto domain,
        @JsonProperty("sequenceNumber") Integer sequenceNumber
) {}
