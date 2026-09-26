package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingAbort;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingComplete;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Siege Phase 6: the match port the runtime uses, wrapped around the HTTP implementation. It adds
 * what DESIGN §7.6 and the plan need so a flaky API never loses a result or blocks the game:
 * <ul>
 *   <li><b>Retry</b> every call with the existing {@link RetryPolicy} (it retries network failures only).</li>
 *   <li><b>Spool</b> a {@code complete}/{@code abort} that still fails transiently (network error, 5xx) to
 *   {@link SiegeResultSpool}; a 4xx is the server's final answer (already finished, unknown match, invalid)
 *   and is only logged. Calls still in flight at shutdown are spooled by {@link #spoolInFlight()}.</li>
 *   <li><b>Replay</b> the spool on startup ({@link #recoverOnStartup()}), then abort every match the API
 *   still has open ({@code ServerRestart}) - but only when no spooled result is left, so a spooled
 *   completion is never aborted first. The spool is replayed again at each draw.</li>
 *   <li><b>createMatch</b> waits for that recovery (so the recovery can't abort a match of this run) and,
 *   when it fails after the retries, completes with {@code null}: the round runs unrecorded (no rewards)
 *   and it is logged as SEVERE.</li>
 * </ul>
 * Every returned future completes normally: failures complete with {@code null} after logging, so the
 * runtime's callbacks never see an exception. No Bukkit types; callers hop to the main thread themselves.
 */
public final class SiegeMatchRecorder implements SiegeMatchesCommandApi {

    /** What startup recovery did. */
    public record RecoveryReport(int replayed, int dropped, int stillPending, List<Long> abortedMatchIds, boolean abortSkipped) {}

    private final SiegeMatchesCommandApi api;
    private final RetryPolicy retryPolicy;
    private final SiegeResultSpool spool;
    private final Logger logger;

    /** complete/abort calls in flight, by match id (spooled by {@link #spoolInFlight()} on shutdown). */
    private final Map<Long, PendingResult> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean replaying = new AtomicBoolean();
    private volatile CompletableFuture<RecoveryReport> recovery = CompletableFuture.completedFuture(null);

    public SiegeMatchRecorder(SiegeMatchesCommandApi api, RetryPolicy retryPolicy, SiegeResultSpool spool, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================== Startup / shutdown ====================

    /**
     * Replays every spooled result, then (when none is left) aborts the matches the API still has open.
     * Call once on enable, before the runtime can draw; {@link #createMatch} waits for it.
     */
    public synchronized CompletableFuture<RecoveryReport> recoverOnStartup() {
        CompletableFuture<RecoveryReport> run = replaySpool().thenCompose(replay -> {
            if (replay.stillPending() > 0) {
                logger.warning("[Siege] " + replay.stillPending() + " spooled match result(s) could not be delivered yet; "
                        + "not aborting unfinished matches this time (they are retried at the next draw and restart)");
                return CompletableFuture.completedFuture(
                        new RecoveryReport(replay.replayed(), replay.dropped(), replay.stillPending(), List.of(), true));
            }
            return retryPolicy.executeAsync(() -> api.abortUnfinished(SiegeEndReason.SERVER_RESTART))
                    .handle((ids, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "[Siege] Startup recovery: aborting unfinished matches failed", unwrap(error));
                            return new RecoveryReport(replay.replayed(), replay.dropped(), 0, List.of(), true);
                        }
                        List<Long> aborted = ids == null ? List.of() : List.copyOf(ids);
                        if (!aborted.isEmpty()) {
                            logger.warning("[Siege] Startup recovery aborted " + aborted.size()
                                    + " match(es) left open by the last run (ServerRestart): " + aborted);
                        }
                        return new RecoveryReport(replay.replayed(), replay.dropped(), 0, aborted, false);
                    });
        });
        recovery = run;
        return run;
    }

    /**
     * Writes every complete/abort still in flight to the spool (call on disable, after the runtime
     * stopped). If the call succeeds after all, the replay is answered as a repeat - harmless.
     */
    public void spoolInFlight() {
        List<PendingResult> pending = new ArrayList<>(inFlight.values());
        for (PendingResult result : pending) {
            if (spool.save(result)) {
                logger.warning("[Siege] Match " + result.matchId() + "'s result was still being sent at shutdown; spooled for the next start");
            }
        }
    }

    /** Replays the spool once (no-op while another replay runs). */
    public CompletableFuture<RecoveryReport> replaySpool() {
        if (!replaying.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new RecoveryReport(0, 0, spool.list().size(), List.of(), true));
        }
        List<PendingResult> pending = spool.list();
        CompletableFuture<int[]> chain = CompletableFuture.completedFuture(new int[3]); // replayed, dropped, stillPending
        for (PendingResult result : pending) {
            chain = chain.thenCompose(counts -> send(result).handle((ignored, error) -> {
                if (error == null) {
                    spool.delete(result.matchId());
                    counts[0]++;
                    logger.info("[Siege] Delivered spooled result of match " + result.matchId());
                } else if (isFinalRejection(error)) {
                    spool.delete(result.matchId());
                    counts[1]++;
                    logger.warning("[Siege] Dropped spooled result of match " + result.matchId()
                            + ": the API refused it (" + describe(error) + ")");
                } else {
                    counts[2]++;
                    logger.log(Level.WARNING, "[Siege] Spooled result of match " + result.matchId() + " still can't be delivered", unwrap(error));
                }
                return counts;
            }));
        }
        return chain.handle((counts, error) -> {
            replaying.set(false);
            if (error != null || counts == null) {
                logger.log(Level.WARNING, "[Siege] Replaying spooled match results failed", error == null ? null : unwrap(error));
                return new RecoveryReport(0, 0, pending.size(), List.of(), true);
            }
            return new RecoveryReport(counts[0], counts[1], counts[2], List.of(), false);
        });
    }

    // ==================== Port ====================

    @Override
    public CompletableFuture<Long> createMatch(int siegeLobbyId, int siegeScenarioId) {
        CompletableFuture<RecoveryReport> gate = recovery.exceptionally(e -> null);
        if (!spool.isEmpty()) {
            replaySpool(); // opportunistic, runs alongside; the new match doesn't depend on it
        }
        return gate.thenCompose(ignored -> retryPolicy.executeAsync(() -> api.createMatch(siegeLobbyId, siegeScenarioId)))
                .handle((id, error) -> {
                    if (error != null || id == null) {
                        logger.log(Level.SEVERE, "[Siege] Could not create a match row for lobby " + siegeLobbyId + " / scenario "
                                + siegeScenarioId + ": this round runs UNRECORDED (no rewards will be granted)",
                                error == null ? null : unwrap(error));
                        return null;
                    }
                    return id;
                });
    }

    @Override
    public CompletableFuture<Void> startMatch(long matchId, List<Participant> participants) {
        return logged("startMatch " + matchId, retryPolicy.executeAsync(() -> api.startMatch(matchId, participants)));
    }

    @Override
    public CompletableFuture<Void> participantLeft(long matchId, int userId, Instant leftAt) {
        return logged("participantLeft " + matchId + "/" + userId,
                retryPolicy.executeAsync(() -> api.participantLeft(matchId, userId, leftAt)));
    }

    /** Completes with the server's summary, or {@code null} when it failed (then spooled unless final). */
    @Override
    public CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion) {
        return deliver(new PendingComplete(matchId, completion)).thenApply(r -> (RewardSummary) r);
    }

    @Override
    public CompletableFuture<Void> abortMatch(long matchId, SiegeEndReason reason) {
        return deliver(new PendingAbort(matchId, reason)).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<List<Long>> abortUnfinished(SiegeEndReason reason) {
        return logged("abortUnfinished", retryPolicy.executeAsync(() -> api.abortUnfinished(reason)));
    }

    // ==================== Internals ====================

    private CompletableFuture<Object> deliver(PendingResult result) {
        inFlight.put(result.matchId(), result);
        return retryPolicy.executeAsync(() -> send(result)).handle((value, error) -> {
            inFlight.remove(result.matchId(), result);
            if (error == null) {
                spool.delete(result.matchId()); // an older spooled attempt for this match is now moot
                return value;
            }
            if (isFinalRejection(error)) {
                logger.log(Level.SEVERE, "[Siege] The API refused " + kind(result) + " for match " + result.matchId()
                        + " (" + describe(error) + "); not retrying", unwrap(error));
            } else if (spool.save(result)) {
                logger.log(Level.WARNING, "[Siege] " + kind(result) + " for match " + result.matchId()
                        + " failed; spooled to " + spool.directory() + " and retried at the next draw/start", unwrap(error));
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> send(PendingResult result) {
        if (result instanceof PendingComplete c) {
            return (CompletableFuture<Object>) (CompletableFuture<?>) api.completeMatch(c.matchId(), c.completion());
        }
        PendingAbort a = (PendingAbort) result;
        return (CompletableFuture<Object>) (CompletableFuture<?>) api.abortMatch(a.matchId(), a.reason());
    }

    private <T> CompletableFuture<T> logged(String what, CompletableFuture<T> future) {
        return future.handle((value, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[Siege] Match API " + what + " failed (" + describe(error) + ")", unwrap(error));
                return null;
            }
            return value;
        });
    }

    /** A 4xx answer: the server has decided (finished already, unknown match, invalid request) - never retried or spooled. */
    public static boolean isFinalRejection(Throwable error) {
        ApiException api = apiException(error);
        return api != null && api.getStatusCode() >= 400 && api.getStatusCode() < 500;
    }

    private static ApiException apiException(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof ApiException api) return api;
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        return null;
    }

    private static String describe(Throwable error) {
        ApiException api = apiException(error);
        if (api != null && api.getStatusCode() > 0) {
            return "HTTP " + api.getStatusCode() + (api.getResponseBody() == null || api.getResponseBody().isBlank() ? "" : ": " + api.getResponseBody());
        }
        Throwable root = unwrap(error);
        return root == null ? "unknown error" : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String kind(PendingResult result) {
        return result instanceof PendingComplete ? "Completing" : "Aborting";
    }
}
