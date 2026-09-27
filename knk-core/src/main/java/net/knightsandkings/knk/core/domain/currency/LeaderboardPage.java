package net.knightsandkings.knk.core.domain.currency;

import java.util.List;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/** A page of /baltop (GET /api/currency/leaderboard). */
public record LeaderboardPage(BalanceCurrency currency, int page, int pageSize, int totalCount, List<LeaderboardEntry> entries) {

    public LeaderboardPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public int totalPages() {
        return pageSize <= 0 ? 1 : Math.max(1, (totalCount + pageSize - 1) / pageSize);
    }
}
