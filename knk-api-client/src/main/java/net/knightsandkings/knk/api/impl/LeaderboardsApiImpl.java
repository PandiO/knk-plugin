package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.LeaderboardDtos;
import net.knightsandkings.knk.api.mapper.LeaderboardMapper;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.LeaderboardsApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * knk-web-api's LeaderboardsController (KNG-34). Board reads act as the viewing player
 * ({@code X-Acting-User-Id}) so the API adds their own row and allows configurable boards; without
 * an acting player only always-public boards answer (401 otherwise).
 */
public class LeaderboardsApiImpl extends BaseApiImpl implements LeaderboardsApi {
    // baseUrl is expected to already include /api
    private static final String LEADERBOARDS_ENDPOINT = "/leaderboards";

    public LeaderboardsApiImpl(
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
    public CompletableFuture<List<LeaderboardBoard>> listBoards() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + LEADERBOARDS_ENDPOINT;
            try {
                return LeaderboardMapper.fromDto(parse(get(url), new TypeReference<List<LeaderboardDtos.Board>>() { }, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the leaderboards", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<LeaderboardView> getBoard(String boardKey, String period, int top, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + LEADERBOARDS_ENDPOINT + "/" + URLEncoder.encode(boardKey, StandardCharsets.UTF_8)
                    + "?period=" + URLEncoder.encode(period == null ? "lifetime" : period, StandardCharsets.UTF_8)
                    + "&top=" + Math.max(1, Math.min(50, top));
            try {
                String json;
                if (actingUserId == null) {
                    json = get(url);
                } else {
                    Request request = newRequest(url)
                            .header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId))
                            .get()
                            .build();
                    if (debugLogging) LOGGER.info("API Request: GET " + url);
                    json = execute(request, url);
                }
                return LeaderboardMapper.fromDto(parse(json, LeaderboardDtos.View.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load leaderboard " + boardKey, e);
            }
        }, executor);
    }
}
