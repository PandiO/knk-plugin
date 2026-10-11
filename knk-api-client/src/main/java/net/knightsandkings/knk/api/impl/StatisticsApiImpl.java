package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.StatisticsDtos;
import net.knightsandkings.knk.api.mapper.StatisticsMapper;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilityConflictException;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.domain.statistics.TitleChange;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * knk-web-api's StatisticsController (KNG-34). A failed call completes exceptionally with the
 * {@link ApiException} (status code kept) in the cause chain; a 409 on a visibility update with a
 * {@link StatisticsVisibilityConflictException} carrying the settings the API holds now. Visibility
 * calls act as the player ({@code X-Acting-User-Id}); the API trusts that header only on a request
 * carrying the plugin's API key.
 */
public class StatisticsApiImpl extends BaseApiImpl implements StatisticsApi {
    // baseUrl is expected to already include /api
    private static final String STATISTICS_ENDPOINT = "/statistics";

    private volatile String serverName;
    private volatile String pluginVersion;

    public StatisticsApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** Sent with every batch (diagnostics only; the API stores the server name on the batch row). */
    public void setSource(String serverName, String pluginVersion) {
        this.serverName = serverName;
        this.pluginVersion = pluginVersion;
    }

    @Override
    public CompletableFuture<StatisticsBatchResult> postBatch(StatisticsBatch batch) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + STATISTICS_ENDPOINT + "/batches";
            try {
                String body = objectMapper.writeValueAsString(StatisticsMapper.toDto(batch, serverName, pluginVersion));
                String json = postJson(url, body);
                return StatisticsMapper.fromDto(parse(json, StatisticsDtos.BatchResult.class, url), batch.batchId());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to send statistics batch " + batch.batchId(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<StatisticsCatalog> getCatalog() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + STATISTICS_ENDPOINT + "/catalog";
            try {
                return StatisticsMapper.fromDto(parse(get(url), StatisticsDtos.Catalog.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the statistics catalog", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<PlayerStatistics> getUserStatistics(int userId, Integer actingUserId, String period, LocalDate date) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder url = new StringBuilder(baseUrl + STATISTICS_ENDPOINT + "/users/" + userId + "?period=")
                    .append(URLEncoder.encode(period == null ? "lifetime" : period, StandardCharsets.UTF_8));
            if (date != null) {
                url.append("&date=").append(date);
            }
            try {
                return StatisticsMapper.fromDto(parse(getActing(url.toString(), actingUserId), StatisticsDtos.PlayerStatistics.class,
                        url.toString()));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the statistics of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Page<TitleChange>> getTitleHistory(int userId, Integer actingUserId, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + STATISTICS_ENDPOINT + "/users/" + userId + "/title-history?page=" + Math.max(1, page)
                    + "&pageSize=" + Math.max(1, pageSize);
            try {
                return StatisticsMapper.fromDto(parse(getActing(url, actingUserId), StatisticsDtos.TitleHistoryPage.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the title history of user " + userId, e);
            }
        }, executor);
    }

    /** GET with {@code X-Acting-User-Id} when a player is viewing; without it the API treats the read as anonymous. */
    private String getActing(String url, Integer actingUserId) throws ApiException, IOException {
        if (actingUserId == null) {
            return get(url);
        }
        Request request = newRequest(url)
                .header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId))
                .get()
                .build();
        if (debugLogging) LOGGER.info("API Request: GET " + url);
        return execute(request, url);
    }

    @Override
    public CompletableFuture<StatisticsVisibilitySettings> getVisibility(int userId, int actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = visibilityUrl(userId);
            try {
                Request request = newRequest(url)
                    .header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId))
                    .get()
                    .build();
                if (debugLogging) LOGGER.info("API Request: GET " + url);
                return StatisticsMapper.fromDto(parse(execute(request, url), StatisticsDtos.Visibility.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the statistics visibility of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<StatisticsVisibilitySettings> updateVisibility(int userId, int actingUserId,
                                                                            List<StatisticsVisibilitySettings.Change> changes) {
        return CompletableFuture.supplyAsync(() -> {
            String url = visibilityUrl(userId);
            try {
                String json = objectMapper.writeValueAsString(StatisticsMapper.toDto(changes));
                Request request = newRequest(url)
                    .header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .put(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
                if (debugLogging) {
                    LOGGER.info("API Request: PUT " + url);
                    LOGGER.info("  Body: " + snippet(json));
                }
                // Not execute(): a 409 body carries the current settings, longer than the logged snippet.
                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.code() == 409) {
                        StatisticsDtos.VisibilityConflict conflict = parse(body, StatisticsDtos.VisibilityConflict.class, url);
                        throw new StatisticsVisibilityConflictException(
                            conflict.message() != null ? conflict.message() : "The settings changed meanwhile",
                            StatisticsMapper.fromDto(conflict.current()));
                    }
                    if (!response.isSuccessful()) {
                        LOGGER.warning(String.format("API Error: PUT %s -> [%d] %s", url, response.code(), snippet(body)));
                        throw new ApiException(url, response.code(), "Request failed", snippet(body));
                    }
                    return StatisticsMapper.fromDto(parse(body, StatisticsDtos.Visibility.class, url));
                }
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to update the statistics visibility of user " + userId, e);
            }
        }, executor);
    }

    private String visibilityUrl(int userId) {
        return baseUrl + STATISTICS_ENDPOINT + "/users/" + userId + "/visibility";
    }
}
