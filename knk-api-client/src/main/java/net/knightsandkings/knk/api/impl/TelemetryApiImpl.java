package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.TelemetryDtos;
import net.knightsandkings.knk.api.mapper.TelemetryMapper;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TelemetryApi;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's TelemetryController (KNG-34 link 6). Failures complete exceptionally; the caller
 * drops the batch. These calls never report themselves as {@code api.call_failed} (that would loop
 * while the API is down): see {@link BaseApiImpl#isTelemetryUrl}.
 */
public class TelemetryApiImpl extends BaseApiImpl implements TelemetryApi {
    // baseUrl is expected to already include /api
    public static final String TELEMETRY_ENDPOINT = "/telemetry";

    public TelemetryApiImpl(
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
    public CompletableFuture<BatchResult> postBatch(List<TelemetryEvent> events) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + TELEMETRY_ENDPOINT + "/events/batch";
            try {
                String body = objectMapper.writeValueAsString(TelemetryMapper.toDtos(events));
                // Event payloads are allowlisted ids/codes, but keep them out of the debug log anyway.
                String json = postJson(url, body, false);
                return TelemetryMapper.fromDto(parse(json, TelemetryDtos.BatchResult.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to send " + events.size() + " diagnostic events", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TelemetryClientConfig> getConfig() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + TELEMETRY_ENDPOINT + "/config";
            try {
                return TelemetryMapper.fromDto(parse(get(url), TelemetryDtos.ClientConfig.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the telemetry config", e);
            }
        }, executor);
    }
}
