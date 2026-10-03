package net.knightsandkings.knk.core.telemetry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One diagnostic event (KNG-34 link 6, knk-workspace docs/specs/player-statistics DESIGN.md §F.12):
 * the envelope knk-web-api's {@code POST api/telemetry/events/batch} takes. Ids, stable codes and
 * allowlisted scalar payload values only - never chat, command arguments, IPs, tokens or bodies (the
 * API drops payload keys it doesn't allow for the name).
 *
 * @param payload scalar values (String, Number, Boolean) by allowlisted key; may be empty
 */
public record TelemetryEvent(
    UUID eventId,
    String name,
    int schemaVersion,
    Instant occurredAt,
    String serverName,
    long serverSeq,
    String appVersion,
    Level level,
    Integer userId,
    UUID sessionKey,
    Integer testRunId,
    Integer matchId,
    String correlationId,
    String feature,
    String action,
    Outcome outcome,
    String reasonCode,
    String objectType,
    String objectId,
    Map<String, Object> payload
) {
    /** Baseline events are sent for everyone; enhanced only for owner-picked players or test runs. */
    public enum Level {
        BASELINE, ENHANCED;

        public String apiName() {
            return this == BASELINE ? "baseline" : "enhanced";
        }
    }

    public enum Outcome {
        SUCCEEDED, DENIED, FAILED, INFO;

        public String apiName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public TelemetryEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(outcome, "outcome");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** The family (part before the first dot), e.g. {@code siege} for {@code siege.match_join}. */
    public String family() {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
