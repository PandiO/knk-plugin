package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Review;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Status;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.UndeliveredItems;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelEscrowPlan;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Escrow recovery (arena duels DESIGN §7.3) and settling a spool file from what the API says:
 * <ol>
 *   <li><b>Startup</b>: replay {@code pending-results/} first (a match that finished just before the stop must be
 *       completed, not aborted), then {@code POST abort-unfinished} (every open match → Aborted, reversed; held
 *       results stay), then ask {@code by-token} for each spool file that isn't settled and settle it:
 *       Completed with a winner → the winner's; Held → kept; anything else (or no record, for a lock-in that never
 *       landed) → each player their own items. Delivery happens on join.</li>
 *   <li><b>Later</b> ({@link #refresh}): a held file after a staff release/void, or a match whose result the API
 *       refused, is settled from its record the same way. A voided result's undelivered items follow
 *       {@code undeliveredItems}: returned to their owners, or destroyed.</li>
 * </ol>
 * A step the API can't answer leaves the file as it is; {@link #needsRetry} tells the ticker to run the startup
 * recovery again. Settling runs on the main thread ({@code mainThread}).
 */
public final class DuelRecovery {

    private final DuelMatchesApi api;
    private final DuelEscrowSpool spool;
    private final DuelPendingResults results;
    private final Executor mainThread;
    private final Logger logger;
    /** Matches the runtime still runs (not settled from a record: their result is on its way). */
    private Predicate<UUID> live = token -> false;
    private volatile boolean startupDone;
    private volatile boolean running;

    public DuelRecovery(DuelMatchesApi api, DuelEscrowSpool spool, DuelPendingResults results, Executor mainThread,
                        Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.results = Objects.requireNonNull(results, "results");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void setLive(Predicate<UUID> live) {
        this.live = live == null ? token -> false : live;
    }

    /** True once the startup recovery got through every step (new lock-ins wait for it). */
    public boolean isStartupDone() {
        return startupDone;
    }

    /** The startup recovery didn't finish (the API was unreachable): run {@link #recoverOnStartup} again. */
    public boolean needsRetry() {
        return !startupDone && !running;
    }

    /**
     * The startup recovery. Main thread: reads the spool, then works asynchronously and settles on the main thread.
     * Completes (never exceptionally) when done or given up on for now.
     */
    public CompletableFuture<Void> recoverOnStartup() {
        if (running) return CompletableFuture.completedFuture(null);
        running = true;
        if (!startupDone) spool.loadAll();
        CompletableFuture<Void> done = new CompletableFuture<>();
        results.replay((pending, result) -> mainThread.execute(() -> settleFromResult(pending.token(), result)))
                .thenCompose(ignored -> results.isEmpty()
                        ? api.abortUnfinished(DuelEndReason.SERVER_RESTART)
                        : CompletableFuture.failedFuture(new IllegalStateException("results still pending")))
                .thenAccept(aborted -> {
                    if (!aborted.isEmpty()) logger.info("[Duel] Aborted unfinished matches after a restart: " + aborted);
                })
                .thenCompose(ignored -> supplyOnMain(this::unsettledTokens))
                .thenCompose(tokens -> {
                    List<CompletableFuture<Boolean>> lookups = new ArrayList<>();
                    for (UUID token : tokens) lookups.add(refresh(token));
                    return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> lookups.stream().allMatch(CompletableFuture::join));
                })
                .whenComplete((allSettled, error) -> {
                    running = false;
                    if (error != null) {
                        logger.log(Level.WARNING, "[Duel] Escrow recovery is waiting for the API; trying again shortly",
                                error);
                    } else if (Boolean.TRUE.equals(allSettled)) {
                        startupDone = true;
                    }
                    done.complete(null);
                });
        return done;
    }

    /**
     * Settle the spool file of {@code token} from the API's record (a held result after staff review, a refused
     * result, recovery). Completes true when the file was settled or needs nothing; false when the API couldn't
     * answer (the file stays).
     */
    public CompletableFuture<Boolean> refresh(UUID token) {
        CompletableFuture<Optional<MatchRecord>> lookup;
        try {
            lookup = api.findByToken(token);
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        return lookup.handle((record, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[Duel] Could not read match " + token + " for its escrow", error);
                return CompletableFuture.completedFuture(false);
            }
            return supplyOnMain(() -> {
                settleFromRecord(token, record.orElse(null));
                return true;
            });
        }).thenCompose(f -> f);
    }

    /** A result the API answered (main thread). A null result (refused) reads the record instead. */
    public void settleFromResult(UUID token, Result result) {
        Optional<DuelEscrowSpool.Entry> entry = spool.find(token);
        if (entry.isEmpty()) return;
        if (result == null || result.match() == null) {
            refresh(token);
            return;
        }
        DuelEscrowPlan plan = result.settlement() == Settlement.HELD
                ? DuelEscrowPlan.hold()
                : planFor(entry.get(), result.match());
        if (plan != null) apply(entry.get(), plan);
    }

    /** Main thread: what the record says about this file, applied. */
    void settleFromRecord(UUID token, MatchRecord record) {
        Optional<DuelEscrowSpool.Entry> found = spool.find(token);
        if (found.isEmpty()) return;
        DuelEscrowSpool.Entry entry = found.get();
        if (entry.state().isFinal()) return;
        if (record != null && record.isOpen() && live.test(token)) return; // the runtime reports it
        if (record != null && record.review() == Review.VOIDED && record.undeliveredItems() == UndeliveredItems.DESTROY) {
            spool.destroy(token, "voided by staff with undelivered items destroyed");
            return;
        }
        DuelEscrowPlan plan = planFor(entry, record);
        if (plan == null) return;
        apply(entry, plan);
    }

    /**
     * DESIGN §7.3 as a decision (null = leave the file as it is: the match is still open on the API and the
     * runtime runs it, which can't happen after {@code abort-unfinished}).
     */
    static DuelEscrowPlan planFor(DuelEscrowSpool.Entry entry, MatchRecord record) {
        if (record == null) return DuelEscrowPlan.refund(null); // a lock-in that never landed
        if (record.review() == Review.HELD) return DuelEscrowPlan.hold();
        if (record.review() == Review.VOIDED) return DuelEscrowPlan.refund(null);
        if (record.isOpen()) return null;
        DuelSide winner = null;
        if (record.status() == Status.COMPLETED && record.winnerUserId() != null) {
            for (DuelSide side : DuelSide.values()) {
                if (entry.party(side).userId() == record.winnerUserId()) winner = side;
            }
        }
        return DuelEscrowPlan.forRecovery(apiStatus(record.status()), winner, false, null);
    }

    private void apply(DuelEscrowSpool.Entry entry, DuelEscrowPlan plan) {
        if (entry.state() == DuelEscrowState.LOCKING && plan.next() != DuelEscrowState.REFUNDED) {
            // Only a refund leaves LOCKING directly; a held or paid result had a match, so mark the lock-in first.
            spool.escrowed(entry.token(), entry.matchId() == null ? 0 : entry.matchId());
        }
        if (!spool.settle(entry.token(), plan)) {
            logger.warning("[Duel] Could not settle the escrow of match " + entry.token() + " (" + plan.next() + ")");
        }
    }

    private List<UUID> unsettledTokens() {
        List<UUID> tokens = new ArrayList<>();
        for (DuelEscrowSpool.Entry entry : spool.entries()) {
            if (!entry.state().isFinal() && !live.test(entry.token())) tokens.add(entry.token());
        }
        return tokens;
    }

    private <T> CompletableFuture<T> supplyOnMain(java.util.function.Supplier<T> step) {
        CompletableFuture<T> future = new CompletableFuture<>();
        mainThread.execute(() -> {
            try {
                future.complete(step.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static String apiStatus(Status status) {
        return switch (status) {
            case CREATED -> "Created";
            case IN_PROGRESS -> "InProgress";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case ABORTED -> "Aborted";
            case UNKNOWN -> "Unknown";
        };
    }

    /** Whether {@code error} is the API saying no (as opposed to not answering). */
    static boolean isRefusal(Throwable error) {
        return DuelRefusedException.find(error) != null;
    }
}
