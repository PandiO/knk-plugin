package net.knightsandkings.knk.api.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shapes of knk-web-api's {@code TelemetryController} (KNG-34 link 6, IMPLEMENTATION_PLAN.md
 * §3.3). Timestamps are ISO-8601 strings (UTC).
 */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("name") String name,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("serverName") String serverName,
        @JsonProperty("serverSeq") long serverSeq,
        @JsonProperty("appVersion") String appVersion,
        @JsonProperty("level") String level,
        @JsonProperty("userId") Integer userId,
        @JsonProperty("sessionKey") String sessionKey,
        @JsonProperty("testRunId") Integer testRunId,
        @JsonProperty("matchId") Integer matchId,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("feature") String feature,
        @JsonProperty("action") String action,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("reasonCode") String reasonCode,
        @JsonProperty("objectType") String objectType,
        @JsonProperty("objectId") String objectId,
        @JsonProperty("payload") Map<String, Object> payload
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rejection(@JsonProperty("index") int index, @JsonProperty("code") String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchResult(
        @JsonProperty("accepted") int accepted,
        @JsonProperty("duplicates") int duplicates,
        @JsonProperty("dropped") int dropped,
        @JsonProperty("rejected") List<Rejection> rejected
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("enhancedUserIds") List<Integer> enhancedUserIds,
        @JsonProperty("activeTestRunIds") List<Integer> activeTestRunIds,
        @JsonProperty("enhancedTestRunIds") List<Integer> enhancedTestRunIds,
        @JsonProperty("baselineEventNames") List<String> baselineEventNames,
        @JsonProperty("enhancedEventNames") List<String> enhancedEventNames
    ) {
    }
}
