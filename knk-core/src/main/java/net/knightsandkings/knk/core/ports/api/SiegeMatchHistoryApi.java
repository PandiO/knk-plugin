package net.knightsandkings.knk.core.ports.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Alpha run 1 (PandiO/knk-workspace#174): a player's finished siege matches for {@code /siege history}, over
 * knk-web-api's public, viewer-filtered {@code GET /api/siege-matches/public/users/{userId}}. The plugin asks as the
 * player themself ({@code X-Acting-User-Id}), so their own numbers are never hidden.
 */
public interface SiegeMatchHistoryApi {

    /**
     * One finished match from the player's side.
     *
     * @param outcome Win, Loss, Draw, Left (left before the end) or Aborted
     * @param kills   null when hidden (never for the player themself)
     */
    record PlayerMatch(
            long matchId,
            String lobbyName,
            String scenarioName,
            Instant endedAt,
            String endReason,
            String teamName,
            String teamChatColor,
            String outcome,
            Integer kills,
            Integer deaths,
            Integer coinsAwarded,
            int expAwarded
    ) {
    }

    /** The player's last {@code limit} finished matches, newest first; empty for none or an unknown player. */
    CompletableFuture<List<PlayerMatch>> playerMatches(int userId, int limit);
}
