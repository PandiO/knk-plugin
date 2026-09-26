package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessDto;
import net.knightsandkings.knk.api.mapper.SiegeMapper;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeScenariosQueryApi;
import okhttp3.OkHttpClient;

/** Siege Phase 4: GET /api/SiegeScenarios/{id}/readiness. A 404 completes with null. */
public class SiegeScenariosQueryApiImpl extends BaseApiImpl implements SiegeScenariosQueryApi {

    public SiegeScenariosQueryApiImpl(
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
    public CompletableFuture<KnkSiegeReadiness> getReadiness(int scenarioId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/SiegeScenarios/" + scenarioId + "/readiness";
            try {
                return SiegeMapper.toCore(parse(get(url), ReadinessDto.class, url));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching siege scenario readiness", e);
            }
        }, executor);
    }
}
