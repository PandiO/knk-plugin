package net.knightsandkings.knk.core.domain.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One flush window of anonymous world analytics (KNG-34 link 7, knk-workspace
 * docs/specs/player-statistics/IMPLEMENTATION_PLAN.md §3.4): the domain mirror of knk-web-api's
 * {@code WorldAnalyticsBatchDto} ({@code POST api/world-analytics/batches}). Idempotent by
 * {@link #batchId()}. Every row belongs to the local day of {@link #windowStart()} - a window never
 * crosses a local midnight. Nothing here identifies a player.
 */
public record WorldAnalyticsBatch(
        UUID batchId,
        Instant windowStart,
        List<MovementCell> movementCells,
        List<MenuStep> menuSteps,
        List<DomainInteraction> domainInteractions
) {
    public WorldAnalyticsBatch {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(windowStart, "windowStart");
        movementCells = movementCells == null ? List.of() : List.copyOf(movementCells);
        menuSteps = menuSteps == null ? List.of() : List.copyOf(menuSteps);
        domainInteractions = domainInteractions == null ? List.of() : List.copyOf(domainInteractions);
    }

    public int rowCount() {
        return movementCells.size() + menuSteps.size() + domainInteractions.size();
    }

    public boolean isEmpty() {
        return rowCount() == 0;
    }

    /** Samples in one cell: blocks [cellX × cellSize, (cellX + 1) × cellSize) of a world. */
    public record MovementCell(String world, int cellSize, int cellX, int cellZ, int samples) {
    }

    /**
     * How often a menu step happened.
     *
     * @param step    {@code opened}, {@code back}, {@code closed} or {@code action:<actionTypeId>}
     * @param outcome {@code info} for opened/back/closed; {@code succeeded}, {@code denied} or {@code failed} for actions
     */
    public record MenuStep(String menuKey, String step, String outcome, int count) {
    }

    /**
     * A domain's entries/exits/discoveries in the window. Exactly one of {@code domainId} (discoveries)
     * and {@code regionId} (WorldGuard region events; the API resolves it to the domain) is set.
     *
     * @param uniquePlayers distinct players of the local day so far (kept in memory only)
     */
    public record DomainInteraction(Integer domainId, String regionId, String kind, int count, int uniquePlayers) {
    }
}
