package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTOs for knk-web-api's {@code /api/leaderboards} endpoints (KNG-34), mirroring
 * {@code Dtos/LeaderboardDtos.cs}. Boxed fields so a missing value maps to the mapper's default.
 */
public final class LeaderboardDtos {
    private LeaderboardDtos() {}

    public record Board(
            @JsonProperty("boardKey") String boardKey,
            @JsonProperty("metric") String metric,
            @JsonProperty("context") String context,
            @JsonProperty("label") String label,
            @JsonProperty("unit") String unit,
            @JsonProperty("periods") List<String> periods,
            @JsonProperty("alwaysPublic") Boolean alwaysPublic
    ) {}

    public record View(
            @JsonProperty("boardKey") String boardKey,
            @JsonProperty("label") String label,
            @JsonProperty("unit") String unit,
            @JsonProperty("period") String period,
            @JsonProperty("periodStart") String periodStart,
            @JsonProperty("generatedAt") String generatedAt,
            @JsonProperty("totalRanked") Integer totalRanked,
            @JsonProperty("entries") List<Entry> entries,
            @JsonProperty("viewer") ViewerEntry viewer
    ) {}

    public record Entry(
            @JsonProperty("rank") Integer rank,
            @JsonProperty("userId") Integer userId,
            @JsonProperty("username") String username,
            @JsonProperty("value") Double value
    ) {}

    public record ViewerEntry(
            @JsonProperty("rank") Integer rank,
            @JsonProperty("value") Double value
    ) {}
}
