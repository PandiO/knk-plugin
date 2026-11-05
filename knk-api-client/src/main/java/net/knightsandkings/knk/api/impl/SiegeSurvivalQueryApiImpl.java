package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PreviewDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ReadinessDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ScenarioSummaryDto;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.OkHttpClient;

/**
 * Siege Survival phase 1 (PandiO/knk-workspace#41, link S4): the survival reads of knk-web-api, read with the plugin's
 * service key. {@code GET /api/siege-survival/scenarios} lists the scenarios that have survival settings;
 * {@code GET /api/siege-survival/scenarios/{id}/configuration?lobbyId=&difficultyProfileId=} is the complete frozen
 * configuration of one match (every default resolved, readiness included) - a 404 means the scenario has no survival
 * settings and plays as ordinary PvP, so it completes with {@code null}; {@code .../readiness} and
 * {@code GET /api/SiegeSurvival/scenarios/{id}/preview} serve the authoring checks. Wire DTOs only: link S5 maps them
 * onto knk-core's survival records (no core port here, so knk-core stays as S1 left it).
 */
public class SiegeSurvivalQueryApiImpl extends BaseApiImpl {

    static final String SCENARIOS_ENDPOINT = "/siege-survival/scenarios";

    public SiegeSurvivalQueryApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** Every scenario with survival settings (enabled or not). */
    public CompletableFuture<List<ScenarioSummaryDto>> listScenarios() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SCENARIOS_ENDPOINT;
            try {
                return parse(get(url), new TypeReference<List<ScenarioSummaryDto>>() { }, url);
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching survival scenarios", e);
            }
        }, executor);
    }

    /**
     * The frozen configuration of a scenario for one match.
     *
     * @param scenarioId          the siege scenario
     * @param lobbyId             the lobby the match runs in (its all-captured override), or null
     * @param difficultyProfileId the difficulty, or null for the scenario's default profile
     * @return the configuration, or null when the scenario has no survival settings (404)
     */
    public CompletableFuture<ConfigurationDto> getConfiguration(int scenarioId, Integer lobbyId, Integer difficultyProfileId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SCENARIOS_ENDPOINT + "/" + scenarioId + "/configuration" + query(lobbyId, difficultyProfileId);
            try {
                return parse(get(url), ConfigurationDto.class, url);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching the survival configuration", e);
            }
        }, executor);
    }

    /** Survival readiness; a 404 (unknown scenario) completes with null. */
    public CompletableFuture<ReadinessDto> getReadiness(int scenarioId, Integer lobbyId, Integer difficultyProfileId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + SCENARIOS_ENDPOINT + "/" + scenarioId + "/readiness" + query(lobbyId, difficultyProfileId);
            try {
                return parse(get(url), ReadinessDto.class, url);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching the survival readiness", e);
            }
        }, executor);
    }

    /** The per-round enemy count and stat multipliers of a difficulty profile (DESIGN §11). */
    public CompletableFuture<PreviewDto> getPreview(int scenarioId, Integer difficultyProfileId, int fromRound, int toRound, int players) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder url = new StringBuilder(baseUrl).append("/SiegeSurvival/scenarios/").append(scenarioId).append("/preview")
                .append("?fromRound=").append(fromRound).append("&toRound=").append(toRound).append("&players=").append(players);
            if (difficultyProfileId != null) url.append("&difficultyProfileId=").append(difficultyProfileId);
            String u = url.toString();
            try {
                return parse(get(u), PreviewDto.class, u);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            } catch (IOException e) {
                throw new ApiException(u, "IO error fetching the survival preview", e);
            }
        }, executor);
    }

    static String query(Integer lobbyId, Integer difficultyProfileId) {
        StringBuilder q = new StringBuilder();
        if (lobbyId != null) q.append(q.length() == 0 ? "?" : "&").append("lobbyId=").append(lobbyId);
        if (difficultyProfileId != null) q.append(q.length() == 0 ? "?" : "&").append("difficultyProfileId=").append(difficultyProfileId);
        return q.toString();
    }
}
