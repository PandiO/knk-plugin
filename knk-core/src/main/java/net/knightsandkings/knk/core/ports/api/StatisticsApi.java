package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;

/**
 * knk-web-api's {@code StatisticsController} (KNG-34, IMPLEMENTATION_PLAN.md §3.1). A failed call
 * completes exceptionally with an {@link net.knightsandkings.knk.core.exception.ApiException} in the
 * cause chain (status code kept), so callers tell a refusal (400) from an unreachable API, a
 * disabled ingestion (503) or a key problem (401/403). The read endpoints for players' statistics
 * are added by link 5.
 */
public interface StatisticsApi {

    /** {@code POST api/statistics/batches} (plugin API key). */
    CompletableFuture<StatisticsBatchResult> postBatch(StatisticsBatch batch);

    /** {@code GET api/statistics/catalog} (anonymous). */
    CompletableFuture<StatisticsCatalog> getCatalog();

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
