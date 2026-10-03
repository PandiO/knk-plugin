package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.WorldAnalyticsDtos;
import net.knightsandkings.knk.api.mapper.WorldAnalyticsMapper;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's WorldAnalyticsController (KNG-34 link 7). A failed call completes exceptionally with
 * the {@link ApiException} (carrying the HTTP status) or the {@link IOException} as the cause.
 */
public class WorldAnalyticsApiImpl extends BaseApiImpl implements WorldAnalyticsApi {
    // baseUrl is expected to already include /api
    public static final String ENDPOINT = "/world-analytics";

    public WorldAnalyticsApiImpl(
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
    public CompletableFuture<BatchResult> postBatch(WorldAnalyticsBatch batch, String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/batches";
            try {
                String body = objectMapper.writeValueAsString(WorldAnalyticsMapper.toDto(batch, serverName));
                // Thousands of cells: keep the body out of the debug log.
                String json = postJson(url, body, false);
                return WorldAnalyticsMapper.fromDto(parse(json, WorldAnalyticsDtos.BatchResult.class, url));
            } catch (ApiException | IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
