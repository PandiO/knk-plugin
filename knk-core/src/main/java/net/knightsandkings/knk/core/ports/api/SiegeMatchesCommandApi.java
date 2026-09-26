package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Write-side port for match lifecycle checkpoints (DESIGN §3.10, §7.6, §11.2), implemented over
 * knk-web-api's {@code /api/siege-matches} endpoints (siege Phase 6). Calls never block the main
 * thread. The server makes start/complete/abort idempotent, so a retry or a spooled replay is safe;
 * {@code SiegeMatchRecorder} (knk-core) adds the retry policy, the pending-results spool and startup
 * recovery around an implementation of this port.
 * <p>
 * Call points: {@link #createMatch} on the draw, {@link #startMatch} at match start,
 * {@link #participantLeft} on leave/quit, {@link #completeMatch} at the end, {@link #abortMatch} on
 * admin stop and shutdown, {@link #abortUnfinished} on startup (after replaying the spool).
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

    /**
     * {@code POST /api/siege-matches/abort-unfinished}: aborts every match still {@code Created} or
     * {@code InProgress} (startup recovery - a restart ends any running match, DESIGN §5.1).
     *
     * @return the ids of the matches it aborted
     */
    CompletableFuture<List<Long>> abortUnfinished(SiegeEndReason reason);
}
