package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;

/**
 * knk-web-api's {@code LeaderboardsController} (KNG-34, IMPLEMENTATION_PLAN.md §3.2): precomputed
 * snapshots, refreshed every few minutes. A failed call completes exceptionally with an
 * {@link net.knightsandkings.knk.core.exception.ApiException} in the cause chain (status kept): 401
 * when a configurable board is read without an acting player, 404 for an unknown board.
 */
public interface LeaderboardsApi {

    /** {@code GET api/leaderboards} (anonymous). */
    CompletableFuture<List<LeaderboardBoard>> listBoards();

    /**
     * {@code GET api/leaderboards/{boardKey}?period=&top=} acting as {@code actingUserId} (null = anonymous):
     * top {@code top} (1-50) plus the viewer's own position.
     */
    CompletableFuture<LeaderboardView> getBoard(String boardKey, String period, int top, Integer actingUserId);
}
