package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PandiO/knk-workspace#180: body of {@code release}, {@code heartbeat} and {@code cancel} on {@code api/WorldTasks/{id}/…}.
 * The API reads the fields each call needs ({@code serverId} for release and heartbeat, {@code reason} for release and cancel).
 *
 * @param handBack #181: on {@code release}, true when the player handed the task back on purpose (in-game {@code cancel}):
 *                 the API then doesn't offer it to them again and the web app shows the task code. Null (omitted) otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorldTaskLeaseDto(
        @JsonProperty("serverId") String serverId,
        @JsonProperty("reason") String reason,
        @JsonProperty("handBack") Boolean handBack
) {
    public WorldTaskLeaseDto(String serverId, String reason) {
        this(serverId, reason, null);
    }
}
