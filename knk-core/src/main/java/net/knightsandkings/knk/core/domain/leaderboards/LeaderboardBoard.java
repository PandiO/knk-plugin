package net.knightsandkings.knk.core.domain.leaderboards;

import java.util.List;

/**
 * One leaderboard of {@code GET api/leaderboards} (KNG-34, DESIGN.md §F.11). {@code context} is null
 * for a total across contexts; {@code alwaysPublic} boards rank everyone, the others only players
 * who set that statistic to Everyone. {@code unit} is the API's name ({@code Count}, {@code Seconds},
 * {@code Blocks}, {@code Points}).
 */
public record LeaderboardBoard(String boardKey, String metric, String context, String label, String unit,
                               List<String> periods, boolean alwaysPublic) {

    public LeaderboardBoard {
        periods = periods == null ? List.of() : List.copyOf(periods);
    }
}
