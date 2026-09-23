package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal id/name/subtype view of a Domain returned by POST api/Domains/search
 * (DomainListDto.domainType server-side) and, for getById, GET api/Domains/{id} - which returns the
 * raw Domain entity and therefore has no domainType, so that field is null in that path.
 */
public record DomainSummaryDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType
) {}
