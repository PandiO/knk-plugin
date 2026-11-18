package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * DTO for WorldTask entity.
 * Represents a task that requires world-bound data to be gathered in-game.
 * PandiO/knk-workspace#180: {@code linkCode} is the task code; {@code claimedByUuid}, {@code expiresAt} and
 * {@code leaseUntil} come with the hardened lifecycle (statuses Pending, InProgress, Completed, Failed, Cancelled, Expired).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldTaskDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("workflowSessionId") Integer workflowSessionId,
        @JsonProperty("entityType") String entityType,
        @JsonProperty("stepNumber") Integer stepNumber,
        @JsonProperty("fieldName") String fieldName,
        @JsonProperty("taskType") String taskType,
        @JsonProperty("status") String status,
        @JsonProperty("linkCode") String linkCode,
        @JsonProperty("claimedByServerId") String claimedByServerId,
        @JsonProperty("claimedByMinecraftUsername") String claimedByMinecraftUsername,
        @JsonProperty("claimedAt") LocalDateTime claimedAt,
        @JsonProperty("completedAt") LocalDateTime completedAt,
        @JsonProperty("inputJson") String inputJson,
        @JsonProperty("outputJson") String outputJson,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("createdAt") LocalDateTime createdAt,
        @JsonProperty("claimedByUuid") String claimedByUuid
) {
    public static final String PENDING = "Pending";
    public static final String IN_PROGRESS = "InProgress";
    public static final String COMPLETED = "Completed";
    public static final String FAILED = "Failed";
    public static final String CANCELLED = "Cancelled";
    public static final String EXPIRED = "Expired";

    /** The pre-#180 shape (no claimer uuid). */
    public WorldTaskDto(Integer id, Integer workflowSessionId, String entityType, Integer stepNumber, String fieldName,
                        String taskType, String status, String linkCode, String claimedByServerId,
                        String claimedByMinecraftUsername, LocalDateTime claimedAt, LocalDateTime completedAt,
                        String inputJson, String outputJson, String errorMessage, LocalDateTime createdAt) {
        this(id, workflowSessionId, entityType, stepNumber, fieldName, taskType, status, linkCode, claimedByServerId,
            claimedByMinecraftUsername, claimedAt, completedAt, inputJson, outputJson, errorMessage, createdAt, null);
    }

    public boolean isPending() {
        return PENDING.equalsIgnoreCase(status);
    }
}
