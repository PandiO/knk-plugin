package net.knightsandkings.knk.core.statistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;

/**
 * Delivers statistics batches (IMPLEMENTATION_PLAN.md §5.2), modelled on the discovery recorder:
 * <ul>
 *   <li><b>Send</b> with the existing {@link RetryPolicy} (it retries network failures only).</li>
 *   <li><b>Spool</b> a batch that still fails transiently - network error, 5xx (including 503
 *   {@code StatisticsDisabled}), 401/403 (the API key doesn't match yet), 408/429 - to
 *   {@link StatisticsSpool}. Any other answer below 500 (400: no batch id, too many entries) is the
 *   server's final word: logged and dropped. Batches still in flight at shutdown are spooled by
 *   {@link #spoolInFlight()}.</li>
 *   <li><b>Rejected entries</b> in a 200 answer are final per entry: logged, never resent.</li>
 *   <li><b>Replay</b> spooled batches oldest first, one at a time, at most {@code maxPerRun} per run;
 *   a run stops at the first transient failure. A batch the API already ingested answers
 *   {@code duplicate: true} - its file is deleted like a delivered one.</li>
 * </ul>
 * Every returned future completes normally. No Bukkit types.
 */
public final class StatisticsRecorder {

    public enum Status {
        /** The API ingested the batch (some entries may have been rejected). */
        DELIVERED,
        /** The API already had this batch id; nothing was applied again. */
        DUPLICATE,
        /** Unreachable/disabled: written to the spool for a later replay. */
        SPOOLED,
        /** Refused (400) or couldn't be spooled: given up. */
        DROPPED
    }

    public record Outcome(Status status, StatisticsBatchResult result) {
    }

    private final StatisticsApi api;
    private final RetryPolicy retryPolicy;
    private final StatisticsSpool spool;
    private final Logger logger;
    private final Map<UUID, StatisticsBatch> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean replaying = new AtomicBoolean();

    public StatisticsRecorder(StatisticsApi api, RetryPolicy retryPolicy, StatisticsSpool spool, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public StatisticsSpool spool() {
        return spool;
    }

    /** Sends one batch; spools it when the API can't take it now. */
    public CompletableFuture<Outcome> send(StatisticsBatch batch) {
        Objects.requireNonNull(batch, "batch");
        inFlight.put(batch.batchId(), batch);
        return call(batch).handle((result, error) -> {
            if (error == null && result != null) {
                logResult(batch, result);
                return new Outcome(result.duplicate() ? Status.DUPLICATE : Status.DELIVERED, result);
            }
            if (error != null && isFinalRejection(error)) {
                logger.log(Level.WARNING, "[Statistics] The API refused batch " + batch.batchId() + " (" + batch.entryCount()
                        + " entries: " + describe(error) + "); dropped", unwrap(error));
                return new Outcome(Status.DROPPED, null);
            }
            if (spool.write(batch)) {
                logger.warning("[Statistics] Could not deliver batch " + batch.batchId() + " (" + batch.entryCount() + " entries: "
                        + describe(error) + "); spooled to " + spool.directory() + " for a later replay");
                return new Outcome(Status.SPOOLED, null);
            }
            return new Outcome(Status.DROPPED, null);
        }).whenComplete((ignored, error) -> inFlight.remove(batch.batchId()));
    }

    /**
     * Writes every batch still being sent to the spool (call on disable, after the final flush had its
     * chance). If one arrives after all, its replay is answered as a duplicate - harmless.
     */
    public void spoolInFlight() {
        for (StatisticsBatch batch : new ArrayList<>(inFlight.values())) {
            if (!spool.contains(batch.batchId()) && spool.write(batch)) {
                logger.warning("[Statistics] Batch " + batch.batchId() + " was still being sent at shutdown; spooled for the next start");
            }
        }
    }

    /**
     * Replays up to {@code maxPerRun} spooled batches, oldest first (no-op while another replay runs).
     * Completes with the number of batches whose files were removed (delivered, duplicate or refused).
     */
    public CompletableFuture<Integer> replay(int maxPerRun) {
        if (!replaying.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        List<StatisticsBatch> batches;
        try {
            batches = spool.list();
        } catch (RuntimeException e) {
            replaying.set(false);
            logger.log(Level.WARNING, "[Statistics] Could not read the statistics spool", e);
            return CompletableFuture.completedFuture(0);
        }
        int limit = Math.max(1, maxPerRun);
        AtomicBoolean stop = new AtomicBoolean();
        int[] removed = {0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (StatisticsBatch batch : batches.subList(0, Math.min(limit, batches.size()))) {
            chain = chain.thenCompose(ignored -> stop.get() ? CompletableFuture.completedFuture(null) : replayOne(batch, stop, removed));
        }
        return chain.handle((ignored, error) -> {
            replaying.set(false);
            if (error != null) {
                logger.log(Level.WARNING, "[Statistics] Replaying spooled statistics failed", unwrap(error));
            }
            synchronized (removed) {
                return removed[0];
            }
        });
    }

    private CompletableFuture<Void> replayOne(StatisticsBatch batch, AtomicBoolean stop, int[] removed) {
        return call(batch).handle((result, error) -> {
            if (error == null && result != null) {
                spool.delete(batch.batchId());
                logResult(batch, result);
                logger.info("[Statistics] Delivered spooled batch " + batch.batchId() + (result.duplicate() ? " (already ingested)" : "")
                        + ": " + result.accepted() + " accepted, " + result.rejected().size() + " rejected");
                increment(removed);
            } else if (error != null && isFinalRejection(error)) {
                spool.delete(batch.batchId());
                logger.warning("[Statistics] Dropped spooled batch " + batch.batchId() + ": the API refused it (" + describe(error) + ")");
                increment(removed);
            } else {
                stop.set(true);
                logger.fine("[Statistics] Spooled statistics still can't be delivered (" + describe(error) + ")");
            }
            return null;
        });
    }

    private CompletableFuture<StatisticsBatchResult> call(StatisticsBatch batch) {
        try {
            CompletableFuture<StatisticsBatchResult> call = retryPolicy.executeAsync(() -> api.postBatch(batch));
            return call == null ? CompletableFuture.failedFuture(new IllegalStateException("no result")) : call;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void logResult(StatisticsBatch batch, StatisticsBatchResult result) {
        if (result.rejected().isEmpty()) {
            logger.fine("[Statistics] Batch " + batch.batchId() + ": " + result.accepted() + " entries accepted"
                    + (result.duplicate() ? " (duplicate)" : ""));
            return;
        }
        String codes = result.rejected().stream()
                .collect(Collectors.groupingBy(r -> r.section() + "/" + r.code(), Collectors.counting()))
                .entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).sorted().collect(Collectors.joining(", "));
        logger.warning("[Statistics] Batch " + batch.batchId() + ": " + result.accepted() + " accepted, "
                + result.rejected().size() + " rejected (final, not resent): " + codes);
    }

    private static void increment(int[] counter) {
        synchronized (counter) {
            counter[0]++;
        }
    }

    /**
     * The server's final answer: any HTTP status below 500 except 401/403 (the key isn't set up yet),
     * 408 and 429. 5xx - including 503 {@code StatisticsDisabled} - and network failures are transient.
     */
    public static boolean isFinalRejection(Throwable error) {
        ApiException api = apiException(error);
        if (api == null || api.getStatusCode() <= 0 || api.getStatusCode() >= 500) {
            return false;
        }
        return switch (api.getStatusCode()) {
            case 401, 403, 408, 429 -> false;
            default -> true;
        };
    }

    static ApiException apiException(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof ApiException api) {
                return api;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return null;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "no result";
        }
        ApiException api = apiException(error);
        if (api != null && api.getStatusCode() > 0) {
            return "HTTP " + api.getStatusCode()
                    + (api.getResponseBody() == null || api.getResponseBody().isBlank() ? "" : ": " + api.getResponseBody());
        }
        Throwable root = unwrap(error);
        return root == null ? "unknown error" : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
