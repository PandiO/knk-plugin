package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for marking a world task as failed. PandiO/knk-workspace#180: {@code serverId}, when sent, must be the server
 * holding the claim.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailTaskDto(
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("serverId") String serverId
) {
    public FailTaskDto(String errorMessage) {
        this(errorMessage, null);
    }
}
