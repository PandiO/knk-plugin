package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Write-side port for match lifecycle checkpoints (DESIGN §3.10, §7.6, §11.2).
 * <b>Interface only in Phase 4</b>: the {@code /api/siege-matches} endpoints and this port's
 * implementation are Phase 6, which may still reshape the records. Calls never block the main
 * thread; Phase 6 retries {@code complete}/{@code abort} with the existing {@code RetryPolicy} and
 * spools failures to {@code siege-vault/pending-results/}. The server makes complete/abort
 * idempotent, so a replay is safe.
 * <p>
 * Call points (Phase 5/6): {@link #createMatch} on the draw, {@link #startMatch} at match start,
 * {@link #participantLeft} on leave/quit, {@link #completeMatch} at the end, {@link #abortMatch} on
 * admin stop, shutdown and startup recovery.
 */
public interface SiegeMatchesCommandApi {
    /** {@code POST /api/siege-matches}: a {@code Created} match row; completes with its id. */
    CompletableFuture<Long> createMatch(int siegeLobbyId, int siegeScenarioId);

    /** {@code POST /api/siege-matches/{id}/start}: {@code InProgress} with participants and teams. */
    CompletableFuture<Void> startMatch(long matchId, List<Participant> participants);

    /** {@code POST /api/siege-matches/{id}/participants/{userId}/left}. */
    CompletableFuture<Void> participantLeft(long matchId, int userId, Instant leftAt);

    /** {@code POST /api/siege-matches/{id}/complete}: grants rewards once; repeats return the stored result. */
    CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion);

    /** {@code POST /api/siege-matches/{id}/abort}: {@code Aborted}, no rewards. */
    CompletableFuture<Void> abortMatch(long matchId, SiegeEndReason reason);
}
