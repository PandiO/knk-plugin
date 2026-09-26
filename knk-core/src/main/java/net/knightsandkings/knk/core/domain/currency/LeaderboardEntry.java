package net.knightsandkings.knk.core.domain.currency;

/** One /baltop line. */
public record LeaderboardEntry(int rank, int userId, String username, long balance) {
}
