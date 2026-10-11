package net.knightsandkings.knk.core.domain.leaderboards;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A board's current snapshot for one period ({@code GET api/leaderboards/{boardKey}}, KNG-34): the
 * top entries (competition ranks, ties share a rank) and the viewer's own position when ranked.
 * {@code generatedAt} is null while no snapshot exists yet. Values are display-rounded by the API.
 */
public record LeaderboardView(String boardKey, String label, String unit, String period, LocalDate periodStart,
                              Instant generatedAt, int totalRanked, List<Entry> entries, ViewerEntry viewer) {

    public LeaderboardView {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Entry(int rank, int userId, String username, double value) {
    }

    public record ViewerEntry(int rank, double value) {
    }
}
