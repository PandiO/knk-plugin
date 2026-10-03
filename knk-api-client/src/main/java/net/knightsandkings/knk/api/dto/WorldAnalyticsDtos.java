package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shapes of knk-web-api's {@code WorldAnalyticsController} (KNG-34 link 7, IMPLEMENTATION_PLAN.md
 * §3.4, knk-web-api Dtos/WorldAnalyticsDtos.cs). Timestamps are ISO-8601 strings (UTC). No shape carries
 * a player identity.
 */
public final class WorldAnalyticsDtos {

    private WorldAnalyticsDtos() {
    }

    public record Batch(
        @JsonProperty("batchId") String batchId,
        @JsonProperty("serverName") String serverName,
        @JsonProperty("windowStart") String windowStart,
        @JsonProperty("movementCells") List<MovementCell> movementCells,
        @JsonProperty("menuSteps") List<MenuStep> menuSteps,
        @JsonProperty("domainInteractions") List<DomainInteraction> domainInteractions
    ) {
    }

    public record MovementCell(
        @JsonProperty("world") String world,
        @JsonProperty("cellSize") int cellSize,
        @JsonProperty("cellX") int cellX,
        @JsonProperty("cellZ") int cellZ,
        @JsonProperty("samples") int samples
    ) {
    }

    public record MenuStep(
        @JsonProperty("menuKey") String menuKey,
        @JsonProperty("step") String step,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("count") int count
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainInteraction(
        @JsonProperty("domainId") Integer domainId,
        @JsonProperty("regionId") String regionId,
        @JsonProperty("kind") String kind,
        @JsonProperty("count") int count,
        @JsonProperty("uniquePlayers") int uniquePlayers
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchResult(
        @JsonProperty("duplicate") boolean duplicate,
        @JsonProperty("accepted") int accepted,
        @JsonProperty("rejected") List<Rejection> rejected
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rejection(
        @JsonProperty("section") String section,
        @JsonProperty("index") int index,
        @JsonProperty("code") String code
    ) {
    }
}
