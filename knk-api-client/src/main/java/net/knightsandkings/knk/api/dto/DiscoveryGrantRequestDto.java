package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST api/users/{userId}/discoveries} (knk-web-api's DiscoveryGrantRequestDto). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryGrantRequestDto(
    @JsonProperty("wgRegionIds") List<String> wgRegionIds,
    @JsonProperty("domainIds") List<Integer> domainIds,
    @JsonProperty("source") String source
) {}
