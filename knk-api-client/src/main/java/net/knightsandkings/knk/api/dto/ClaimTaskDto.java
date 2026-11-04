package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST api/WorldTasks/{id}/claim}. PandiO/knk-workspace#180: the API stores the server, player and uuid
 * (before, the field names didn't match the API and nothing was stored) and checks the task code on a code claim.
 *
 * @param claimMode {@link #MODE_CODE}, {@link #MODE_ID} (the player holds {@code knk.tasks.any}), {@link #MODE_HEADLESS}
 *                  (the server runs a scan itself) or {@link #MODE_OFFER} (#181)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimTaskDto(
        @JsonProperty("taskCode") String taskCode,
        @JsonProperty("claimedByServerId") String serverId,
        @JsonProperty("claimedByMinecraftUsername") String minecraftUsername,
        @JsonProperty("claimedByUuid") String uuid,
        @JsonProperty("claimMode") String claimMode
) {
    public static final String MODE_CODE = "code";
    public static final String MODE_ID = "id";
    public static final String MODE_HEADLESS = "headless";
    public static final String MODE_OFFER = "offer";

    /** The older three-argument shape (a code claim without the player's uuid). */
    public ClaimTaskDto(String linkCode, String serverId, String minecraftUsername) {
        this(linkCode, serverId, minecraftUsername, null, linkCode == null ? null : MODE_CODE);
    }

    public static ClaimTaskDto byCode(String taskCode, String serverId, String username, String uuid) {
        return new ClaimTaskDto(taskCode, serverId, username, uuid, MODE_CODE);
    }

    public static ClaimTaskDto byId(String serverId, String username, String uuid) {
        return new ClaimTaskDto(null, serverId, username, uuid, MODE_ID);
    }

    public static ClaimTaskDto headless(String serverId) {
        return new ClaimTaskDto(null, serverId, null, null, MODE_HEADLESS);
    }
}
