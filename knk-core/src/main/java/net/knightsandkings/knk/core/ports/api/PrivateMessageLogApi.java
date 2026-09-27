package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;

/**
 * knk-web-api's server-side private message log (PrivateMessageLogController,
 * {@code POST api/private-message-log/batch}; docs/specs/private-messages/DESIGN.md §3.2). The API
 * only accepts the plugin's service key (KNG-22).
 */
public interface PrivateMessageLogApi {

    /** At most this many entries per call (the API refuses larger batches). */
    int MAX_BATCH_SIZE = 200;

    /** How many entries the API stored and how many it already had (a re-sent batch). */
    record BatchResult(int accepted, int duplicates) {
    }

    /**
     * Sends one batch. Fails exceptionally with a {@link net.knightsandkings.knk.core.exception.ApiException}
     * (possibly wrapped) carrying the HTTP status when the API refuses it.
     */
    CompletableFuture<BatchResult> submitBatch(List<PrivateMessageLogEntry> entries);
}
