package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi;
import okhttp3.OkHttpClient;

/** {@code GET /api/Domains/world/missing} and {@code POST /api/Domains/world/backfill} (KNG-111/112). */
public class DomainWorldBackfillApiImpl extends BaseApiImpl implements DomainWorldBackfillApi {

    static final String MISSING_ENDPOINT = "/Domains/world/missing";
    static final String BACKFILL_ENDPOINT = "/Domains/world/backfill";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MissingDto(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("candidateWorlds") List<String> candidateWorlds
    ) {
        MissingDomain toDomain() {
            return new MissingDomain(id, name, domainType, wgRegionId, candidateWorlds == null ? List.of() : List.copyOf(candidateWorlds));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultDto(
        @JsonProperty("updated") int updated,
        @JsonProperty("unresolved") List<MissingDto> unresolved
    ) { }

    record RegionWorldsDto(
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("worlds") List<String> worlds
    ) { }

    record RequestDto(@JsonProperty("regions") List<RegionWorldsDto> regions) { }

    public DomainWorldBackfillApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                      AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<MissingDomain>> listMissing() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + MISSING_ENDPOINT;
            try {
                List<MissingDto> missing = parse(get(url), new TypeReference<List<MissingDto>>() { }, url);
                return missing == null ? List.<MissingDomain>of() : missing.stream().map(MissingDto::toDomain).toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the domains without a world", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Result> backfill(Map<String, List<String>> worldsByRegionId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BACKFILL_ENDPOINT;
            List<RegionWorldsDto> regions = new ArrayList<>();
            worldsByRegionId.forEach((regionId, worlds) -> regions.add(new RegionWorldsDto(regionId, List.copyOf(worlds))));
            try {
                String body = objectMapper.writeValueAsString(new RequestDto(regions));
                ResultDto result = parse(postJson(url, body), ResultDto.class, url);
                if (result == null) {
                    return new Result(0, List.of());
                }
                List<MissingDomain> unresolved = result.unresolved() == null ? List.of()
                    : result.unresolved().stream().map(MissingDto::toDomain).toList();
                return new Result(result.updated(), unresolved);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the region world report", e);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to send the region world report", e);
            }
        }, executor);
    }
}
