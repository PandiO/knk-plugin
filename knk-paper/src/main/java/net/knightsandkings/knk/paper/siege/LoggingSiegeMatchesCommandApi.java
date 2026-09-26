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
 * A no-op match API that only logs (siege Phase 5's placeholder). Since Phase 6 the plugin uses
 * knk-api-client's {@code SiegeMatchesCommandApiImpl} wrapped in {@code SiegeMatchRecorder}; this
 * class is kept for running the runtime without recording matches (e.g. a test server without the
 * API). {@link #createMatch} hands out <b>negative</b> ids so a log line can never be mistaken for a
 * real match row; {@link #completeMatch} returns an empty {@link RewardSummary} (no rewards shown).
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

    @Override
    public CompletableFuture<List<Long>> abortUnfinished(SiegeEndReason reason) {
        logger.info("[Siege][match-api:no-op] abortUnfinished reason=" + reason);
        return CompletableFuture.completedFuture(List.of());
    }
}
