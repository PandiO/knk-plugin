package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** knk-web-api {@code PermissionGroupRefDto} (KNG-52: {@code UserSummaryDto.permissionGroups}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PermissionGroupRefDto(@JsonProperty("id") int id, @JsonProperty("name") String name) {
}
