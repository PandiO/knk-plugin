package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi;
import okhttp3.OkHttpClient;

/** knk-web-api's MinecraftCatalogController (KNG-200/KNG-250 catalogue scan). */
public class MinecraftCatalogApiImpl extends BaseApiImpl implements MinecraftCatalogApi {
    // baseUrl is expected to already include /api
    public static final String ENDPOINT = "/MinecraftCatalog";
    // The API applies the snapshot before it answers (icon lookup plus one transaction over ~2k rows), which
    // can take longer than the shared client's read timeout.
    static final Duration SNAPSHOT_READ_TIMEOUT = Duration.ofMinutes(2);

    public MinecraftCatalogApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient.newBuilder().readTimeout(SNAPSHOT_READ_TIMEOUT).build(),
            objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Void> putSnapshot(int scanId, MinecraftCatalogSnapshotDto snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/scans/" + scanId + "/snapshot";
            try {
                String body = objectMapper.writeValueAsString(snapshot);
                // About 1.5k entries plus the enchantments (~0.5 MB): keep the body out of the debug log.
                putJson(url, body, false);
                return null;
            } catch (ApiException | IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
