package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeConfigDto;
import net.knightsandkings.knk.api.mapper.SiegeMapper;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeLobbiesQueryApi;
import okhttp3.OkHttpClient;

/** Siege Phase 4: GET /api/SiegeLobbies/runtime-config (DESIGN §11.2). */
public class SiegeLobbiesQueryApiImpl extends BaseApiImpl implements SiegeLobbiesQueryApi {

    public SiegeLobbiesQueryApiImpl(
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
    public CompletableFuture<KnkSiegeRuntimeConfig> getRuntimeConfig() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/SiegeLobbies/runtime-config";
            try {
                return SiegeMapper.toCore(parse(get(url), RuntimeConfigDto.class, url));
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching siege runtime config", e);
            }
        }, executor);
    }
}
