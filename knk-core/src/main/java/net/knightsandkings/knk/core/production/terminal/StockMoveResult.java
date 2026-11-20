package net.knightsandkings.knk.core.production.terminal;

import java.util.List;

/**
 * The API's answer to a terminal put or take ({@code StockResultDto}): {@code status} Applied, PartiallyApplied or
 * Rejected; {@code accepted} units moved; the reasons of rejected lines.
 */
public record StockMoveResult(String status, boolean replayed, long accepted, List<String> rejectReasons) {

    public StockMoveResult {
        rejectReasons = rejectReasons == null ? List.of() : List.copyOf(rejectReasons);
    }

    public boolean rejected() {
        return accepted <= 0;
    }
}
