package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import net.knightsandkings.knk.api.dto.EnchantmentCombatValuesDto;
import net.knightsandkings.knk.api.dto.EnchantmentDefinitionDto;
import net.knightsandkings.knk.api.dto.EnchantmentDefinitionListDtoPagedResultDto;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.mapper.EnchantmentDefinitionMapper;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.EnchantmentDefinitionsQueryApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

public class EnchantmentDefinitionsQueryApiImpl implements EnchantmentDefinitionsQueryApi {
    private static final Logger LOGGER = Logger.getLogger(EnchantmentDefinitionsQueryApiImpl.class.getName());

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthProvider authProvider;
    private final ExecutorService executor;
    private final boolean debugLogging;

    private static final String BASE_ENDPOINT = "/EnchantmentDefinitions";
    private static final String SEARCH_ENDPOINT = "/EnchantmentDefinitions/search";
    private static final int MAX_RESPONSE_SNIPPET_LENGTH = 200;

    public EnchantmentDefinitionsQueryApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.executor = executor;
        this.debugLogging = debugLogging;
    }

    @Override
    public CompletableFuture<Page<KnkEnchantmentDefinition>> search(net.knightsandkings.knk.core.domain.common.PagedQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SEARCH_ENDPOINT;
            long startTime = System.currentTimeMillis();

            try {
                PagedQueryDto dto = new PagedQueryDto(
                        query.pageNumber(),
                        query.pageSize(),
                        query.searchTerm(),
                        query.sortBy(),
                        query.sortDescending(),
                        query.filters()
                );

                String requestBody = objectMapper.writeValueAsString(dto);

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.get("application/json")));

                if (authProvider != null && authProvider.getAuthHeader() != null) {
                    requestBuilder.addHeader(authProvider.getAuthHeaderName(), authProvider.getAuthHeader());
                }

                Request request = requestBuilder.build();

                if (debugLogging) {
                    LOGGER.info("API Request: POST " + url);
                    LOGGER.info("  Body: " + requestBody);
                }

                try (Response response = httpClient.newCall(request).execute()) {
                    long latency = System.currentTimeMillis() - startTime;
                    if (debugLogging) {
                        LOGGER.info(String.format("API Response: POST %s [%d] in %dms", url, response.code(), latency));
                    }

                    String responseBody = response.body() != null ? response.body().string() : "";

                    if (!response.isSuccessful()) {
                        String snippet = responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                        if (responseBody.length() > MAX_RESPONSE_SNIPPET_LENGTH) snippet += "...";
                        throw new ApiException(url, response.code(), "Search enchantment definitions failed", snippet);
                    }

                    if (responseBody.isEmpty()) {
                        throw new ApiException(url, response.code(), "Empty response body", "");
                    }

                    try {
                        EnchantmentDefinitionListDtoPagedResultDto result = objectMapper.readValue(
                                responseBody,
                                EnchantmentDefinitionListDtoPagedResultDto.class
                        );
                        return EnchantmentDefinitionMapper.mapPagedList(result);
                    } catch (Exception parseEx) {
                        LOGGER.warning("Failed to parse response: " + responseBody);
                        throw new ApiException(
                                url,
                                response.code(),
                                "Failed to parse response: " + parseEx.getMessage(),
                                responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH))
                        );
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                        url,
                        0,
                        "IO error during search enchantment definitions",
                        e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkEnchantmentDefinition> getById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + id;
            long startTime = System.currentTimeMillis();

            Request.Builder requestBuilder = new Request.Builder().url(url);
            if (authProvider != null && authProvider.getAuthHeader() != null) {
                requestBuilder.addHeader(authProvider.getAuthHeaderName(), authProvider.getAuthHeader());
            }

            Request request = requestBuilder.build();
            if (debugLogging) {
                LOGGER.info("API Request: GET " + url);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                long latency = System.currentTimeMillis() - startTime;
                if (debugLogging) {
                    LOGGER.info(String.format("API Response: GET %s [%d] in %dms", url, response.code(), latency));
                }

                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    String snippet = responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                    if (responseBody.length() > MAX_RESPONSE_SNIPPET_LENGTH) snippet += "...";
                    throw new ApiException(url, response.code(), "Get enchantment definition by ID failed", snippet);
                }

                if (responseBody.isEmpty()) {
                    throw new ApiException(url, response.code(), "Empty response body", "");
                }

                try {
                    EnchantmentDefinitionDto dto = objectMapper.readValue(responseBody, EnchantmentDefinitionDto.class);
                    return EnchantmentDefinitionMapper.toCore(dto);
                } catch (Exception parseEx) {
                    LOGGER.warning("Failed to parse response: " + responseBody);
                    throw new ApiException(
                            url,
                            response.code(),
                            "Failed to parse response: " + parseEx.getMessage(),
                            responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH))
                    );
                }
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(
                        url,
                        0,
                        "IO error during get enchantment definition by ID",
                        e.getClass().getSimpleName() + ": " + e.getMessage()
                );
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    /**
     * PandiO/knk-workspace#151: GET /EnchantmentDefinitions (every definition, plugin key) and keeps the combat values
     * of the custom ones.
     */
    @Override
    public CompletableFuture<List<EnchantmentTuningOverride>> listCombatValues() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT;
            Request.Builder requestBuilder = new Request.Builder().url(url).addHeader("Accept", "application/json");
            if (authProvider != null && authProvider.getAuthHeader() != null) {
                requestBuilder.addHeader(authProvider.getAuthHeaderName(), authProvider.getAuthHeader());
            }
            if (debugLogging) {
                LOGGER.info("API Request: GET " + url);
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String snippet = responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                    throw new ApiException(url, response.code(), "List enchantment definitions failed", snippet);
                }
                if (responseBody.isEmpty()) {
                    throw new ApiException(url, response.code(), "Empty response body", "");
                }
                List<EnchantmentCombatValuesDto> dtos;
                try {
                    dtos = objectMapper.readValue(responseBody, new TypeReference<List<EnchantmentCombatValuesDto>>() {});
                } catch (Exception parseEx) {
                    throw new ApiException(url, response.code(), "Failed to parse response: " + parseEx.getMessage(),
                            responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH)));
                }
                return toOverrides(dtos);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                ApiException apiEx = new ApiException(url, 0, "IO error during list enchantment definitions",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                apiEx.initCause(e);
                throw apiEx;
            }
        }, executor);
    }

    /** The custom definitions' combat values; vanilla definitions carry none. */
    static List<EnchantmentTuningOverride> toOverrides(List<EnchantmentCombatValuesDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .filter(Objects::nonNull)
                .filter(dto -> Boolean.TRUE.equals(dto.isCustom()) && dto.key() != null)
                .map(dto -> new EnchantmentTuningOverride(
                        dto.key(),
                        dto.procChancePercent(),
                        dto.procChancePerLevelPercent(),
                        dto.cooldownEnabled(),
                        dto.cooldownSeconds(),
                        dto.effectDurationSeconds(),
                        dto.levelMultiplier()))
                .toList();
    }
}
