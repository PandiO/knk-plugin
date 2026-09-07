package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id;
            
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
    public CompletableFuture<Void> updateGateState(int id, boolean isOpened, boolean isDestroyed, boolean isJammed) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id + "/state";

            try {
                Map<String, Boolean> payload = new HashMap<>();
                payload.put("isOpened", isOpened);
                payload.put("isDestroyed", isDestroyed);
                payload.put("isJammed", isJammed);
                String json = objectMapper.writeValueAsString(payload);
                
                Request request = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .put(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
                
                if (debugLogging) LOGGER.info("API Request: PUT " + url);
                execute(request, url);
                
                return null;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error updating gate state",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateOperationalSettings(int id, boolean isActive, boolean isInvincible) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id + "/operational-settings";

            try {
                Map<String, Boolean> payload = new HashMap<>();
                payload.put("isActive", isActive);
                payload.put("isInvincible", isInvincible);
                String json = objectMapper.writeValueAsString(payload);
                Request request = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .put(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
                execute(request, url);
                return null;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate operational settings",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateGateHealth(int id, double healthCurrent) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + id + "/health";

            try {
                Map<String, Double> payload = new HashMap<>();
                payload.put("healthCurrent", healthCurrent);
                String json = objectMapper.writeValueAsString(payload);
                Request request = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .put(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
                execute(request, url);
                return null;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate health",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<GateBlockSnapshotDto>> getGateSnapshots(int gateId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + gateId + "/snapshots";
            
            try {
                String responseBody = get(url);
                JsonNode response = objectMapper.readTree(responseBody);
                JsonNode snapshots = response.isArray() ? response : response.path("value");
                if (!snapshots.isArray()) {
                    throw new IOException("Expected an array or an object containing a value array");
                }
                return objectMapper.convertValue(snapshots, new TypeReference<List<GateBlockSnapshotDto>>() {});
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "IO error fetching gate snapshots",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(
                    url,
                    0,
                    "Failed to parse gate snapshots response: " + e.getMessage(),
                    e.getClass().getSimpleName()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }
}
