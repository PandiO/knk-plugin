package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PandiO/knk-workspace#180: body of {@code release}, {@code heartbeat} and {@code cancel} on {@code api/WorldTasks/{id}/…}.
 * The API reads the fields each call needs ({@code serverId} for release and heartbeat, {@code reason} for release and cancel).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorldTaskLeaseDto(
        @JsonProperty("serverId") String serverId,
        @JsonProperty("reason") String reason
) {
}
