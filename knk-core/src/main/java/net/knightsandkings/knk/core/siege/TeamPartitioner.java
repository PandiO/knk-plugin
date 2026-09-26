package net.knightsandkings.knk.core.siege;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Team split at T-10 (DESIGN §6.4): a <b>snake draft by title bracket</b>. Players are ordered
 * strongest first (equal ranks in random order), then dealt to the teams in scenario order,
 * reversing direction every round: 1-2-3, 3-2-1, 1-2-3, ... Team sizes differ by at most one, and
 * every team gets a player before any gets a second.
 * <p>
 * Replaces v2's {@code Partition.ofSize(members, ceil(members / teams))}, which yields fewer chunks
 * than teams for e.g. 4 players / 3 teams (chunk size 2 → 2 chunks) and then threw on
 * {@code partition.get(2)}.
 */
public final class TeamPartitioner {
    private TeamPartitioner() {}

    /**
     * @param playerId the member
     * @param rank     higher = stronger; pass the player's title bracket (e.g. its MinExperience or
     *                 its position in the bracket list), not raw XP, so players in the same bracket
     *                 are shuffled rather than ordered
     */
    public record Entry(UUID playerId, int rank) {
        public Entry {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /**
     * @param teamIds the scenario's teams in order (sortOrder, id); must not be empty
     * @return every team id (in the given order) mapped to its players, strongest first
     */
    public static Map<Integer, List<UUID>> partition(List<Entry> players, List<Integer> teamIds, RandomGenerator random) {
        if (teamIds.isEmpty()) throw new IllegalArgumentException("A siege split needs at least one team");

        List<Entry> ordered = new ArrayList<>(players);
        shuffle(ordered, random);
        ordered.sort(Comparator.comparingInt(Entry::rank).reversed()); // stable: equal ranks stay shuffled

        Map<Integer, List<UUID>> teams = new LinkedHashMap<>();
        teamIds.forEach(id -> teams.put(id, new ArrayList<>()));

        int n = teamIds.size();
        for (int i = 0; i < ordered.size(); i++) {
            int round = i / n;
            int position = i % n;
            int teamIndex = round % 2 == 0 ? position : n - 1 - position;
            teams.get(teamIds.get(teamIndex)).add(ordered.get(i).playerId());
        }

        Map<Integer, List<UUID>> result = new LinkedHashMap<>();
        teams.forEach((id, members) -> result.put(id, Collections.unmodifiableList(members)));
        return Collections.unmodifiableMap(result);
    }

    private static <T> void shuffle(List<T> list, RandomGenerator random) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }
}
