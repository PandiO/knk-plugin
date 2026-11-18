package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for completing a world task with output data. PandiO/knk-workspace#180: {@code serverId}, when sent, must be the
 * server holding the claim.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompleteTaskDto(
        @JsonProperty("outputJson") String outputJson,
        @JsonProperty("serverId") String serverId
) {
    public CompleteTaskDto(String outputJson) {
        this(outputJson, null);
    }
}
