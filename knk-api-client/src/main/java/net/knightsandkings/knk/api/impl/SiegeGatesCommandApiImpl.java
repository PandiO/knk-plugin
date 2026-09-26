package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.DoorState;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.LockdownEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.RestoreResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.RestoredGate;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeGatesCommandApi;
import okhttp3.OkHttpClient;

/**
 * Siege Phase 7a: the persisted gate lockdown over {@code /api/siege-matches} (DESIGN §8.2, §8.4),
 * mirroring knk-web-api's {@code Dtos/SiegeMatchGateDtos.cs}. Door open states go as the API's
 * {@code GateDoorOpenState} names ({@code "OPEN"}/{@code "CLOSED"}).
 */
public class SiegeGatesCommandApiImpl extends BaseApiImpl implements SiegeGatesCommandApi {

    private static final String BASE_ENDPOINT = "/siege-matches";

    // ---- wire DTOs ----

    record DoorStateDto(
            @JsonProperty("gateDoorId") Integer gateDoorId,
            @JsonProperty("openedState") String openedState,
            @JsonProperty("healthCurrent") Double healthCurrent,
            @JsonProperty("isDestroyed") Boolean isDestroyed
    ) {}

    record LockdownEntryDto(
            @JsonProperty("gateStructureId") int gateStructureId,
            @JsonProperty("isObjectiveGate") boolean isObjectiveGate,
            @JsonProperty("invincible") boolean invincible,
            @JsonProperty("forcedOpen") boolean forcedOpen,
            @JsonProperty("doors") List<DoorStateDto> doors
    ) {}

    record LockdownRequestDto(@JsonProperty("gates") List<LockdownEntryDto> gates) {}

    record SnapshotContentDto(@JsonProperty("doors") List<DoorStateDto> doors) {}

    record SnapshotDto(
            @JsonProperty("siegeMatchId") Integer siegeMatchId,
            @JsonProperty("gateStructureId") Integer gateStructureId,
            @JsonProperty("snapshot") SnapshotContentDto snapshot
    ) {}

    record RestoreResultDto(
            @JsonProperty("restored") List<SnapshotDto> restored,
            @JsonProperty("clearedGateStructureIds") List<Integer> clearedGateStructureIds
    ) {}

    public SiegeGatesCommandApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Void> lockdown(long matchId, List<LockdownEntry> gates) {
        return CompletableFuture.supplyAsync(() -> {
            List<LockdownEntryDto> entries = gates == null ? List.of() : gates.stream()
                    .map(g -> new LockdownEntryDto(g.gateStructureId(), g.objectiveGate(), g.invincible(), g.forcedOpen(),
                            g.doors().stream().map(SiegeGatesCommandApiImpl::toDto).toList()))
                    .toList();
            post(baseUrl + BASE_ENDPOINT + "/" + matchId + "/gate-lockdown", new LockdownRequestDto(entries));
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<RestoreResult> restore(long matchId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + matchId + "/gate-restore";
            return toCore(parse(post(url, "{}"), RestoreResultDto.class, url));
        }, executor);
    }

    @Override
    public CompletableFuture<RestoreResult> restoreStale() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/restore-stale-gates";
            return toCore(parse(post(url, "{}"), RestoreResultDto.class, url));
        }, executor);
    }

    // ---- mapping ----

    static DoorStateDto toDto(DoorState door) {
        return new DoorStateDto(door.gateDoorId(), door.opened() ? "OPEN" : "CLOSED", door.healthCurrent(), door.destroyed());
    }

    static DoorState toCore(DoorStateDto dto) {
        String state = dto.openedState() == null ? "CLOSED" : dto.openedState().trim().toUpperCase(java.util.Locale.ROOT);
        return new DoorState(dto.gateDoorId() == null ? 0 : dto.gateDoorId(),
                state.equals("OPEN") || state.equals("OPENING"),
                dto.healthCurrent() == null ? 0 : dto.healthCurrent(),
                Boolean.TRUE.equals(dto.isDestroyed()));
    }

    static RestoreResult toCore(RestoreResultDto dto) {
        if (dto == null) return new RestoreResult(List.of(), List.of());
        List<RestoredGate> restored = dto.restored() == null ? List.of() : dto.restored().stream()
                .filter(s -> s != null && s.gateStructureId() != null)
                .map(s -> new RestoredGate(s.siegeMatchId() == null ? 0 : s.siegeMatchId(), s.gateStructureId(),
                        s.snapshot() == null || s.snapshot().doors() == null ? List.of()
                                : s.snapshot().doors().stream().filter(d -> d != null && d.gateDoorId() != null)
                                        .map(SiegeGatesCommandApiImpl::toCore).toList()))
                .toList();
        List<Integer> cleared = dto.clearedGateStructureIds() == null ? List.of()
                : dto.clearedGateStructureIds().stream().filter(java.util.Objects::nonNull).toList();
        return new RestoreResult(restored, cleared);
    }

    private String post(String url, Object body) {
        try {
            String json = body instanceof String s ? s : objectMapper.writeValueAsString(body);
            return postJson(url, json);
        } catch (IOException e) {
            throw new ApiException(url, "IO error calling the siege gate API", e);
        }
    }
}
