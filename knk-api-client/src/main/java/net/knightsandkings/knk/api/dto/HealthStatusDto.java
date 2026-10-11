package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for health status response from API.
 * <p>
 * knk-web-api's {@code /health/ready} serializes PascalCase ({@code "Status"}, {@code "Version"}),
 * unlike the camelCase {@code /api} routes, hence the aliases (KNG-115 live test).
 */
public record HealthStatusDto(
    @JsonProperty("status") @JsonAlias("Status") String status,
    @JsonProperty("version") @JsonAlias("Version") String version
) {}
