package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;

/**
 * knk-web-api's {@code TelemetryController}, plugin side (KNG-34 link 6, IMPLEMENTATION_PLAN.md
 * §3.3). Both calls use the plugin API key. Failures complete exceptionally; callers drop the batch
 * (telemetry is never retried or spooled).
 */
public interface TelemetryApi {

    /** Result of one batch: queued, already stored, dropped by a full API queue, rejected by validation. */
    record BatchResult(int accepted, int duplicates, int dropped, int rejected) {
    }

    /** {@code POST api/telemetry/events/batch} (≤ 500 events). */
    CompletableFuture<BatchResult> postBatch(List<TelemetryEvent> events);

    /** {@code GET api/telemetry/config}. */
    CompletableFuture<TelemetryClientConfig> getConfig();
}
