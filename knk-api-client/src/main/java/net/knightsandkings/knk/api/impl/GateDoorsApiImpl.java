package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.GateDoorDto;
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
 * HTTP client implementation for GateDoorsApi.
 * Provides access to gate door endpoints in the Web API.
 */
public class GateDoorsApiImpl extends BaseApiImpl implements GateDoorsApi {

    private static final String GATE_STRUCTURES_ENDPOINT = "/GateStructures";
    private static final String GATE_DOORS_ENDPOINT = "/GateDoors";

    public GateDoorsApiImpl(
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
    public CompletableFuture<List<GateDoorDto>> getByStructureId(int gateStructureId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_STRUCTURES_ENDPOINT + "/" + gateStructureId + "/doors";

            try {
                String responseBody = get(url);
                return objectMapper.readValue(responseBody, new TypeReference<List<GateDoorDto>>() {});
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error fetching gate doors",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(url, 0, "Failed to parse gate doors response: " + e.getMessage(),
                    e.getClass().getSimpleName());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<GateDoorDto> getById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_DOORS_ENDPOINT + "/" + id;

            try {
                String responseBody = get(url);
                return objectMapper.readValue(responseBody, GateDoorDto.class);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error fetching gate door by ID",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            } catch (Exception e) {
                ApiException apiEx = new ApiException(url, 0, "Failed to parse gate door response: " + e.getMessage(),
                    e.getClass().getSimpleName());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateState(int id, String openedState, boolean isDestroyed) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_DOORS_ENDPOINT + "/" + id + "/state";

            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("openedState", openedState);
                payload.put("isDestroyed", isDestroyed);
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
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate door state",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateOperationalSettings(int id, boolean isActive, boolean isInvincible) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_DOORS_ENDPOINT + "/" + id + "/operational-settings";

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
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate door operational settings",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateHealth(int id, double healthCurrent) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_DOORS_ENDPOINT + "/" + id + "/health";

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
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate door health",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateRegionData(int id, boolean isOpenedRegion, String regionData) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + GATE_DOORS_ENDPOINT + "/" + id + "/region";

            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("isOpenedRegion", isOpenedRegion);
                payload.put("regionData", regionData);
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
                ApiException apiEx = new ApiException(url, 0, "IO error updating gate door region data",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }
}
