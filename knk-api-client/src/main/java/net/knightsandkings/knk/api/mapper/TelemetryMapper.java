package net.knightsandkings.knk.api.mapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.api.dto.TelemetryDtos;
import net.knightsandkings.knk.core.ports.api.TelemetryApi;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;

/** Domain ↔ wire for diagnostic telemetry (KNG-34 link 6). */
public final class TelemetryMapper {

    private TelemetryMapper() {
    }

    public static TelemetryDtos.Event toDto(TelemetryEvent e) {
        return new TelemetryDtos.Event(
            e.eventId().toString(),
            e.name(),
            e.schemaVersion(),
            e.occurredAt().toString(),
            e.serverName(),
            e.serverSeq(),
            e.appVersion(),
            e.level().apiName(),
            e.userId(),
            e.sessionKey() == null ? null : e.sessionKey().toString(),
            e.testRunId(),
            e.matchId(),
            e.correlationId(),
            e.feature(),
            e.action(),
            e.outcome().apiName(),
            e.reasonCode(),
            e.objectType(),
            e.objectId(),
            e.payload().isEmpty() ? null : Map.copyOf(e.payload()));
    }

    public static List<TelemetryDtos.Event> toDtos(List<TelemetryEvent> events) {
        return events.stream().map(TelemetryMapper::toDto).toList();
    }

    public static TelemetryApi.BatchResult fromDto(TelemetryDtos.BatchResult dto) {
        if (dto == null) {
            return new TelemetryApi.BatchResult(0, 0, 0, 0);
        }
        return new TelemetryApi.BatchResult(dto.accepted(), dto.duplicates(), dto.dropped(),
            dto.rejected() == null ? 0 : dto.rejected().size());
    }

    public static TelemetryClientConfig fromDto(TelemetryDtos.ClientConfig dto) {
        if (dto == null) {
            return TelemetryClientConfig.initial();
        }
        return new TelemetryClientConfig(
            dto.enabled(),
            dto.enhancedUserIds() == null ? null : new HashSet<>(dto.enhancedUserIds()),
            dto.activeTestRunIds(),
            dto.enhancedTestRunIds() == null ? null : new HashSet<>(dto.enhancedTestRunIds()),
            dto.baselineEventNames() == null ? null : new HashSet<>(dto.baselineEventNames()),
            dto.enhancedEventNames() == null ? null : new HashSet<>(dto.enhancedEventNames()));
    }
}
