package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;

/**
 * knk-web-api's {@code WorldAnalyticsController}, plugin side (KNG-34 link 7, IMPLEMENTATION_PLAN.md
 * §3.4). Uses the plugin API key. Failures complete exceptionally with the
 * {@link net.knightsandkings.knk.core.exception.ApiException} (status code) as a cause; the caller
 * keeps transient failures in memory and retries (same batch id), drops refused ones.
 */
public interface WorldAnalyticsApi {

    /** Result of one batch: already ingested, rows applied, rows refused by validation. */
    record BatchResult(boolean duplicate, int accepted, int rejected) {
    }

    /** {@code POST api/world-analytics/batches}. */
    CompletableFuture<BatchResult> postBatch(WorldAnalyticsBatch batch, String serverName);
}
