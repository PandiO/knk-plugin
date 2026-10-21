package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi;
import okhttp3.OkHttpClient;

/** knk-web-api's MinecraftCatalogController (KNG-200 catalogue scan). */
public class MinecraftCatalogApiImpl extends BaseApiImpl implements MinecraftCatalogApi {
    // baseUrl is expected to already include /api
    public static final String ENDPOINT = "/MinecraftCatalog";

    public MinecraftCatalogApiImpl(
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
    public CompletableFuture<Void> putSnapshot(int scanId, MinecraftCatalogSnapshotDto snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/scans/" + scanId + "/snapshot";
            try {
                String body = objectMapper.writeValueAsString(snapshot);
                // About 1.5k entries (~0.5 MB): keep the body out of the debug log.
                putJson(url, body, false);
                return null;
            } catch (ApiException | IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
