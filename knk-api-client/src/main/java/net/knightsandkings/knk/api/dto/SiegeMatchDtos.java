package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire DTOs for knk-web-api's {@code /api/siege-matches} endpoints (siege Phase 6), mirroring
 * {@code Dtos/SiegeMatchDtos.cs}. JSON is camelCase; enums go as the API's PascalCase names
 * ({@code "InstantVictory"}); instants go as ISO-8601 strings (the client's ObjectMapper would
 * otherwise write numeric timestamps, which the API's DateTime binding rejects). Response types use
 * boxed fields so a missing value maps to the mapper's default.
 */
public final class SiegeMatchDtos {
    private SiegeMatchDtos() {}

    // ---- requests ----

    public record CreateRequest(
            @JsonProperty("siegeLobbyId") int siegeLobbyId,
            @JsonProperty("siegeScenarioId") int siegeScenarioId
    ) {}

    public record ParticipantStart(
            @JsonProperty("userId") int userId,
            @JsonProperty("siegeTeamId") int siegeTeamId
    ) {}

    public record StartRequest(@JsonProperty("participants") List<ParticipantStart> participants) {}

    public record LeftRequest(@JsonProperty("leftAt") String leftAt) {}

    public record ParticipantResult(
            @JsonProperty("userId") int userId,
            @JsonProperty("siegeTeamId") int siegeTeamId,
            @JsonProperty("kills") int kills,
            @JsonProperty("deaths") int deaths,
            @JsonProperty("highestKillStreak") int highestKillStreak,
            @JsonProperty("captures") int captures
    ) {}

    public record ObjectiveResult(
            @JsonProperty("siegeObjectiveId") int siegeObjectiveId,
            @JsonProperty("finalHolderTeamId") Integer finalHolderTeamId,
            @JsonProperty("capturedByUserId") Integer capturedByUserId,
            @JsonProperty("capturedAt") String capturedAt
    ) {}

    public record CompleteRequest(
            @JsonProperty("endReason") String endReason,
            @JsonProperty("winningAllianceGroup") Integer winningAllianceGroup,
            @JsonProperty("participants") List<ParticipantResult> participants,
            @JsonProperty("objectives") List<ObjectiveResult> objectives
    ) {}

    public record AbortRequest(@JsonProperty("endReason") String endReason) {}

    // ---- responses ----

    public record MatchResponse(
            @JsonProperty("id") Integer id,
            @JsonProperty("status") String status
    ) {}

    public record Reward(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("presentAtEnd") Boolean presentAtEnd,
            @JsonProperty("won") Boolean won,
            @JsonProperty("holdingCount") Integer holdingCount,
            @JsonProperty("captureCount") Integer captureCount,
            @JsonProperty("coins") Integer coins,
            @JsonProperty("experience") Integer experience,
            @JsonProperty("gems") Integer gems,
            @JsonProperty("coinMultiplier") Double coinMultiplier
    ) {}

    public record ResultResponse(
            @JsonProperty("matchId") Integer matchId,
            @JsonProperty("status") String status,
            @JsonProperty("alreadyCompleted") Boolean alreadyCompleted,
            @JsonProperty("rewards") List<Reward> rewards
    ) {}

    public record AbortUnfinishedResponse(@JsonProperty("abortedMatchIds") List<Integer> abortedMatchIds) {}
}
