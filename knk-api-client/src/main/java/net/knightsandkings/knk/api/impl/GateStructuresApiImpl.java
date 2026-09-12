package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.dto.GateStructureOverridesUpdateDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * HTTP client implementation for GateStructuresApi.
 * Provides access to gate structure endpoints in the Web API.
 */
public class GateStructuresApiImpl extends BaseApiImpl implements GateStructuresApi {

    private static final String GATE_STRUCTURES_ENDPOINT = "/GateStructures";

    public GateStructuresApiImpl(
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
    public CompletableFuture<List<GateStructureDto>> getAll() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT;

            try {
                String responseBody = get(url);
                return objectMapper.readValue(responseBody, new TypeReference<List<GateStructureDto>>() {});
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error fetching gate structures",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "Failed to parse gate structures response: " + e.getMessage(),
                    e.getClass().getSimpleName()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<GateStructureDto> getById(int id) {
        return fetchById(id, false);
    }

    @Override
    public CompletableFuture<GateStructureDto> getByIdWithSnapshots(int id) {
        return fetchById(id, true);
    }

    private CompletableFuture<GateStructureDto> fetchById(int id, boolean includeSnapshots) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id
                + (includeSnapshots ? "?includeSnapshots=true" : "");

            try {
                String responseBody = get(url);
                return objectMapper.readValue(responseBody, GateStructureDto.class);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error fetching gate structure by ID",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "Failed to parse gate structure response: " + e.getMessage(),
                    e.getClass().getSimpleName()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<GateStructureDto>> getByDistrict(int districtId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "?districtId=" + districtId
                + "&pageNumber=1&pageSize=500";

            try {
                String responseBody = get(url);
                PagedResultDto<GateStructureDto> page = objectMapper.readValue(
                    responseBody, new TypeReference<PagedResultDto<GateStructureDto>>() {});
                return page.items() != null ? page.items() : List.<GateStructureDto>of();
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error fetching gate structures by district",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "Failed to parse gate structures by district response: " + e.getMessage(),
                    e.getClass().getSimpleName()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateOverrides(int id, GateStructureOverridesUpdateDto request) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id + "/overrides";

            try {
                String json = objectMapper.writeValueAsString(request);
                Request httpRequest = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .patch(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
                if (debugLogging) LOGGER.info("API Request: PATCH " + url);
                execute(httpRequest, url);
                return null;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate structure overrides",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }
}
