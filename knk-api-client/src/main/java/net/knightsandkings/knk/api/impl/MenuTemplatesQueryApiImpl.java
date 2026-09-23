package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.MenuTemplateDto;
import net.knightsandkings.knk.api.dto.MenuTemplateListDto;
import net.knightsandkings.knk.api.mapper.MenuTemplateMapper;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

public class MenuTemplatesQueryApiImpl implements MenuTemplatesQueryApi {
    private static final Logger LOGGER = Logger.getLogger(MenuTemplatesQueryApiImpl.class.getName());

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthProvider authProvider;
    private final ExecutorService executor;
    private final boolean debugLogging;

    private static final String BASE_ENDPOINT = "/MenuTemplates";
    private static final int MAX_RESPONSE_SNIPPET_LENGTH = 200;

    public MenuTemplatesQueryApiImpl(
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
    public CompletableFuture<KnkMenuTemplate> getById(int id) {
        String url = baseUrl + BASE_ENDPOINT + "/" + id;
        return CompletableFuture.supplyAsync(() -> executeGet(url, MenuTemplateDto.class, "get menu template by id"), executor)
                .thenApply(MenuTemplateMapper::toCore);
    }

    @Override
    public CompletableFuture<KnkMenuTemplate> getByKey(String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String url = baseUrl + BASE_ENDPOINT + "/by-key/" + encodedKey;
        return CompletableFuture.supplyAsync(() -> executeGet(url, MenuTemplateDto.class, "get menu template by key"), executor)
                .thenApply(MenuTemplateMapper::toCore);
    }

    @Override
    public CompletableFuture<List<KnkMenuTemplateSummary>> listAll() {
        String url = baseUrl + BASE_ENDPOINT;
        return CompletableFuture.supplyAsync(() -> {
            MenuTemplateListDto[] result = executeGet(url, MenuTemplateListDto[].class, "list menu templates");
            if (result == null) {
                return Collections.<KnkMenuTemplateSummary>emptyList();
            }
            return java.util.Arrays.stream(result).map(MenuTemplateMapper::toCore).toList();
        }, executor);
    }

    private <T> T executeGet(String url, Class<T> responseType, String operationDescription) {
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

            if (response.code() == 404) {
                return null;
            }

            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                String snippet = responseBody.substring(0, Math.min(responseBody.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                if (responseBody.length() > MAX_RESPONSE_SNIPPET_LENGTH) snippet += "...";
                throw new ApiException(url, response.code(), "Failed to " + operationDescription, snippet);
            }

            if (responseBody.isEmpty()) {
                throw new ApiException(url, response.code(), "Empty response body", "");
            }

            try {
                return objectMapper.readValue(responseBody, responseType);
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
                    "IO error during " + operationDescription,
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
            apiEx.initCause(e);
            throw apiEx;
        }
    }
}
