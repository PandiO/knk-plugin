package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Completion;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.FightStart;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.LockRequest;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Telemetry;
import net.knightsandkings.knk.core.duel.DuelEndReason;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Arena duel match records over knk-web-api's {@code /api/DuelMatches} (arena duels DESIGN.md §6.5, §7, §10.3;
 * KNG-213). Calls never block the main thread. Every write carries an {@code Idempotency-Key} derived from the
 * match token ({@code duel-lock:<token>}, {@code duel-complete:<token>}, ...), and the API is idempotent on the
 * match state, so a retry or a spooled replay returns the stored result ({@link Result#alreadyDone()}).
 * <p>
 * Failures: a refusal is a {@link net.knightsandkings.knk.core.domain.duels.DuelRefusedException} (never retry);
 * network errors and 5xx are {@link net.knightsandkings.knk.core.exception.ApiException}s with status -1 / 5xx
 * (retry with the same call). The runtime (P4, KNG-217) owns the retry policy, the pending-results spool and the
 * startup recovery around this port.
 */
public interface DuelMatchesApi {
    /** {@code POST /api/DuelMatches/check}: the lock-in rules without writing (invite and accept pre-checks, §6.1). */
    CompletableFuture<CheckResult> check(LockRequest request);

    /** {@code POST /api/DuelMatches}: lock-in; the stakes are escrowed in SYS_DUEL. A repeated token returns the stored match. */
    CompletableFuture<MatchRecord> lock(LockRequest request);

    /** {@code GET /api/DuelMatches/by-token/{token}}: empty when no match has the token (a lock-in that never landed). */
    CompletableFuture<Optional<MatchRecord>> findByToken(UUID token);

    /** {@code POST /api/DuelMatches/{id}/start-fight}. */
    CompletableFuture<MatchRecord> startFight(int matchId, UUID token, FightStart start);

    /** {@code POST /api/DuelMatches/{id}/complete}: a Win or Forfeit; the result says PAID or HELD. */
    CompletableFuture<Result> complete(int matchId, UUID token, Completion completion);

    /**
     * {@code POST /api/DuelMatches/{id}/cancel}: a draw, cancel or abort ({@code reason} not decisive); the stake is
     * reversed once and the result says REFUNDED.
     */
    CompletableFuture<Result> cancel(int matchId, UUID token, DuelEndReason reason, Telemetry telemetry);

    /** {@code POST /api/DuelMatches/abort-unfinished}: startup recovery; returns the match ids it aborted. Held results stay. */
    CompletableFuture<List<Integer>> abortUnfinished(DuelEndReason reason);

    /** {@code POST /api/DuelMatches/{id}/release} for {@code /knk duel review <match> release} (X-Acting-User-Id = the staff member). */
    CompletableFuture<Result> release(int matchId, int actingUserId, String reason);

    /** {@code POST /api/DuelMatches/{id}/void}: a held result refunded; {@code reason} is required. */
    CompletableFuture<Result> voidHeld(int matchId, int actingUserId, String reason);
}
