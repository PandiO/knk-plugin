package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** knk-web-api Dtos/LocationRetentionDtos.cs (KNG-80): only the fields the game server reads. */
public final class LocationRetentionDtos {
    private LocationRetentionDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreviousDecision(
        @JsonProperty("decidedByUsername") String decidedByUsername,
        @JsonProperty("decisionNote") String decisionNote
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Orphan(
        @JsonProperty("id") int id,
        @JsonProperty("locationId") int locationId,
        @JsonProperty("status") String status,
        @JsonProperty("world") String world,
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("z") double z,
        @JsonProperty("flaggedAt") String flaggedAt,
        @JsonProperty("previousDecision") PreviousDecision previousDecision
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrphanPage(
        @JsonProperty("items") List<Orphan> items,
        @JsonProperty("totalCount") int totalCount,
        @JsonProperty("pageNumber") int pageNumber,
        @JsonProperty("pageSize") int pageSize,
        @JsonProperty("openCount") int openCount
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeleportTarget(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("world") String world,
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("z") double z,
        @JsonProperty("yaw") float yaw,
        @JsonProperty("pitch") float pitch
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DigestNotification(
        @JsonProperty("runId") int runId,
        @JsonProperty("newCount") int newCount,
        @JsonProperty("openCount") int openCount
    ) {}
}
