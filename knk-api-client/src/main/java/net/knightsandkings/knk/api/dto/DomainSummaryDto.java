package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal id/name/subtype view of a Domain returned by POST api/Domains/search
 * (DomainListDto.domainType server-side) and, for getById, GET api/Domains/{id} - which returns the
 * raw Domain entity and therefore has no domainType, so that field is null in that path.
 * {@code navigationDefault} is where {@code /navigate <domain>} leads without {@code spawn}/{@code region}:
 * "Spawn" or "Region", the domain's override else its type's default (KNG-73); search only, null otherwise.
 * {@code roadAccess} is "Applies" or "Ignored": whether the domain's entry/exit rule keeps the road router
 * off its roads (rev. 7 Part C, KNG-92); search only, null otherwise.
 */
public record DomainSummaryDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("navigationDefault") String navigationDefault,
        @JsonProperty("roadAccess") String roadAccess
) {}
