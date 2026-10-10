package net.knightsandkings.knk.core.ports.api;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.domain.statistics.TitleChange;

/**
 * knk-web-api's {@code StatisticsController} (KNG-34, IMPLEMENTATION_PLAN.md §3.1). A failed call
 * completes exceptionally with an {@link net.knightsandkings.knk.core.exception.ApiException} in the
 * cause chain (status code kept), so callers tell a refusal (400) from an unreachable API, a
 * disabled ingestion (503) or a key problem (401/403). Reads act as the viewing player
 * ({@code X-Acting-User-Id}) so the API applies the target's visibility settings for that viewer; a
 * null acting user (the console) reads as an anonymous visitor (always-public fields only).
 */
public interface StatisticsApi {

    /** {@code POST api/statistics/batches} (plugin API key). */
    CompletableFuture<StatisticsBatchResult> postBatch(StatisticsBatch batch);

    /** {@code GET api/statistics/catalog} (anonymous). */
    CompletableFuture<StatisticsCatalog> getCatalog();

    /**
     * {@code GET api/statistics/users/{userId}?period=&date=}: what {@code actingUserId} may see.
     * {@code period} is {@code lifetime}, {@code day}, {@code week} or {@code month}; {@code date}
     * (nullable) a local day inside it. A 404 (unknown/inactive user) completes exceptionally.
     */
    CompletableFuture<PlayerStatistics> getUserStatistics(int userId, Integer actingUserId, String period, LocalDate date);

    /**
     * {@code GET api/statistics/users/{userId}/title-history} (newest first). A 403 (the player keeps
     * it private) completes exceptionally with the {@code ApiException} status 403 in the cause chain.
     */
    CompletableFuture<Page<TitleChange>> getTitleHistory(int userId, Integer actingUserId, int page, int pageSize);

    /** {@code GET api/statistics/users/{userId}/visibility} acting as {@code actingUserId} (the player themselves). */
    CompletableFuture<StatisticsVisibilitySettings> getVisibility(int userId, int actingUserId);

    /**
     * {@code PUT api/statistics/users/{userId}/visibility}: one atomic update (≤ 64 changes). A 409
     * completes exceptionally with a
     * {@link net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilityConflictException} in
     * the cause chain carrying the current settings.
     */
    CompletableFuture<StatisticsVisibilitySettings> updateVisibility(int userId, int actingUserId,
                                                                     List<StatisticsVisibilitySettings.Change> changes);
}
