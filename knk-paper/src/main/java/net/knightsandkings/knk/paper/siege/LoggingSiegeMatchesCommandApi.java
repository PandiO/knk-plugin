package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * <b>PHASE 6 PLACEHOLDER - replace with the real {@code SiegeMatchesCommandApiImpl}.</b>
 * <p>
 * The {@code /api/siege-matches} endpoints don't exist yet (siege IMPLEMENTATION_PLAN Phase 6), so
 * this logs every checkpoint and records nothing. {@link #createMatch} hands out provisional
 * <b>negative</b> ids so a log line can never be mistaken for a real match row.
 * {@link #completeMatch} returns an empty {@link RewardSummary}: the runtime then prints its own
 * provisional breakdown instead ({@code ProvisionalRewardCalculator}).
 * <p>
 * Phase 6: build the HTTP implementation in knk-api-client and pass it to {@code SiegeService}
 * in {@code KnKPlugin} instead of this class; the call sites don't change.
 */
public final class LoggingSiegeMatchesCommandApi implements SiegeMatchesCommandApi {

    private final Logger logger;
    private final AtomicLong nextProvisionalId = new AtomicLong(-1);

    public LoggingSiegeMatchesCommandApi(Logger logger) {
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Long> createMatch(int siegeLobbyId, int siegeScenarioId) {
        long id = nextProvisionalId.getAndDecrement();
        logger.info("[Siege][match-api:no-op] createMatch lobby=" + siegeLobbyId + " scenario=" + siegeScenarioId
                + " -> provisional id " + id);
        return CompletableFuture.completedFuture(id);
    }

    @Override
    public CompletableFuture<Void> startMatch(long matchId, List<Participant> participants) {
        logger.info("[Siege][match-api:no-op] startMatch " + matchId + " participants=" + participants);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> participantLeft(long matchId, int userId, Instant leftAt) {
        logger.info("[Siege][match-api:no-op] participantLeft " + matchId + " user=" + userId + " at " + leftAt);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion) {
        logger.info("[Siege][match-api:no-op] completeMatch " + matchId + " " + completion);
        return CompletableFuture.completedFuture(new RewardSummary(matchId, List.of()));
    }

    @Override
    public CompletableFuture<Void> abortMatch(long matchId, SiegeEndReason reason) {
        logger.info("[Siege][match-api:no-op] abortMatch " + matchId + " reason=" + reason);
        return CompletableFuture.completedFuture(null);
    }
}
