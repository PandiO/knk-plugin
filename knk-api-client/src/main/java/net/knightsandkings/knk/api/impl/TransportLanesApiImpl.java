package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TransportLanesApi;
import net.knightsandkings.knk.core.production.transport.LaneDue;
import net.knightsandkings.knk.core.production.transport.LaneMeasurement;
import net.knightsandkings.knk.core.production.transport.LanePosition;
import net.knightsandkings.knk.core.production.transport.LaneSettings;
import net.knightsandkings.knk.core.production.transport.RoadConnection;
import okhttp3.OkHttpClient;

/**
 * Transport lanes (PandiO/knk-workspace#681): the contract of knk-web-api P5 (#79, comment on #681):
 * {@code GET api/TransportLanes/due}, {@code POST api/TransportLanes/report} and {@code PUT
 * api/ProductionStructures/{id}/road-connection}. Rows without both structure ids are dropped while reading; an
 * unreachable lane is sent without {@code roadDistance}.
 */
public class TransportLanesApiImpl extends BaseApiImpl implements TransportLanesApi {

    static final String DUE_ENDPOINT = "/TransportLanes/due";
    static final String REPORT_ENDPOINT = "/TransportLanes/report";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionDto(@JsonProperty("world") String world, @JsonProperty("x") Double x, @JsonProperty("y") Double y,
                       @JsonProperty("z") Double z) {
        LanePosition toPosition() {
            if (world == null || world.isBlank() || x == null || y == null || z == null) {
                return null;
            }
            return new LanePosition(world, x, y, z);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DueDto(@JsonProperty("fromStructureId") Integer fromStructureId,
                  @JsonProperty("toStructureId") Integer toStructureId,
                  @JsonProperty("fromStructureName") String fromStructureName,
                  @JsonProperty("toStructureName") String toStructureName,
                  @JsonProperty("fromName") String fromName, @JsonProperty("toName") String toName,
                  @JsonProperty("from") PositionDto from, @JsonProperty("to") PositionDto to,
                  @JsonProperty("reason") String reason, @JsonProperty("lastMeasuredAt") String lastMeasuredAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LaneDto(@JsonProperty("fromStructureId") int fromStructureId, @JsonProperty("toStructureId") int toStructureId,
                   @JsonProperty("reachable") boolean reachable, @JsonProperty("roadDistance") Double roadDistance,
                   @JsonProperty("straightDistance") Double straightDistance,
                   @JsonProperty("routeEdgeIds") List<Integer> routeEdgeIds,
                   @JsonProperty("measuredAt") String measuredAt) {
    }

    record ReportDto(@JsonProperty("lanes") List<LaneDto> lanes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportResultDto(@JsonProperty("created") int created, @JsonProperty("updated") int updated) {
    }

    record RoadConnectionDto(@JsonProperty("roadConnection") String roadConnection) {
    }

    public TransportLanesApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                 AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<LaneDue>> due(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + DUE_ENDPOINT + "?limit=" + Math.max(1, limit);
            try {
                List<DueDto> rows = parse(get(url), new TypeReference<List<DueDto>>() { }, url);
                List<LaneDue> due = new ArrayList<>();
                for (DueDto row : rows == null ? List.<DueDto>of() : rows) {
                    if (row == null || row.fromStructureId() == null || row.toStructureId() == null
                            || row.fromStructureId() <= 0 || row.toStructureId() <= 0) {
                        continue;
                    }
                    due.add(new LaneDue(row.fromStructureId(), row.toStructureId(),
                            row.fromStructureName() != null ? row.fromStructureName() : row.fromName(),
                            row.toStructureName() != null ? row.toStructureName() : row.toName(),
                            row.from() == null ? null : row.from().toPosition(),
                            row.to() == null ? null : row.to().toPosition(), row.reason(), instant(row.lastMeasuredAt())));
                }
                return due;
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the lane list", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ReportResult> report(List<LaneMeasurement> lanes) {
        if (lanes.size() > LaneSettings.MAX_REPORT) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "at most " + LaneSettings.MAX_REPORT + " lanes per report, not " + lanes.size()));
        }
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + REPORT_ENDPOINT;
            try {
                String body = objectMapper.writeValueAsString(toDto(lanes));
                ReportResultDto result = parse(postJson(url, body, false), ReportResultDto.class, url);
                return result == null ? new ReportResult(0, 0) : new ReportResult(result.created(), result.updated());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the lane report", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send the lane report", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> setRoadConnection(int structureId, RoadConnection state) {
        return CompletableFuture.runAsync(() -> {
            String url = baseUrl + "/ProductionStructures/" + structureId + "/road-connection";
            try {
                putJson(url, objectMapper.writeValueAsString(new RoadConnectionDto(state.name())));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the road connection", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send the road connection", e);
            }
        }, executor);
    }

    static ReportDto toDto(List<LaneMeasurement> lanes) {
        return new ReportDto(lanes.stream().map(l -> new LaneDto(l.fromStructureId(), l.toStructureId(), l.reachable(),
                l.reachable() ? l.roadDistance() : null, l.straightDistance(),
                l.routeEdgeIds().isEmpty() ? null : l.routeEdgeIds(),
                (l.measuredAt() == null ? Instant.now() : l.measuredAt()).toString())).toList());
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
