package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.TeamPartitioner.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the snake-draft team split (DESIGN §6.4) and the v2 {@code Partition} crash. */
class TeamPartitionerTest {

    private static List<Entry> players(int count, int rank) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new Entry(player(i), rank)).toList();
    }

    @Test
    void fourPlayersIntoThreeTeamsGivesEveryTeamAPlayer() {
        // v2: Partition.ofSize(members, ceil(4 / 3) = 2) -> 2 chunks, then partition.get(2) threw.
        int v2ChunkSize = (int) Math.ceil(4 / 3.0);
        int v2Chunks = (int) Math.ceil(4 / (double) v2ChunkSize);
        assertEquals(2, v2Chunks, "the v2 split produced fewer chunks than teams");

        Map<Integer, List<UUID>> teams = TeamPartitioner.partition(players(4, 0), List.of(10, 20, 30), new Random(7));

        assertEquals(List.of(10, 20, 30), new ArrayList<>(teams.keySet()));
        // Snake: round 1 deals T10, T20, T30; round 2 starts with T30, which picked last.
        assertEquals(List.of(1, 1, 2), teams.values().stream().map(List::size).toList());
        Set<UUID> assigned = new HashSet<>();
        teams.values().forEach(assigned::addAll);
        assertEquals(4, assigned.size());
    }

    @Test
    void dealsStrongestFirstAndReversesEveryRound() {
        List<Entry> players = List.of(
                new Entry(player(1), 10), new Entry(player(2), 60), new Entry(player(3), 30),
                new Entry(player(4), 50), new Entry(player(5), 20), new Entry(player(6), 40));

        Map<Integer, List<UUID>> teams = TeamPartitioner.partition(players, List.of(1, 2, 3), new Random(1));

        // Round 1: 60->T1, 50->T2, 40->T3. Round 2 reversed: 30->T3, 20->T2, 10->T1.
        assertEquals(List.of(player(2), player(1)), teams.get(1));
        assertEquals(List.of(player(4), player(5)), teams.get(2));
        assertEquals(List.of(player(6), player(3)), teams.get(3));
    }

    @Test
    void sizesNeverDifferByMoreThanOne() {
        for (int teamCount = 2; teamCount <= 4; teamCount++) {
            List<Integer> teamIds = IntStream.rangeClosed(1, teamCount).boxed().toList();
            for (int n = 0; n <= 20; n++) {
                Map<Integer, List<UUID>> teams = TeamPartitioner.partition(players(n, 0), teamIds, new Random(n));
                int min = teams.values().stream().mapToInt(List::size).min().orElseThrow();
                int max = teams.values().stream().mapToInt(List::size).max().orElseThrow();
                assertTrue(max - min <= 1, n + " players / " + teamCount + " teams");
                assertEquals(n, teams.values().stream().mapToInt(List::size).sum());
            }
        }
    }

    @Test
    void equalBracketsAreShuffled() {
        Set<List<UUID>> firstTeams = new HashSet<>();
        for (long seed = 0; seed < 30; seed++) {
            firstTeams.add(TeamPartitioner.partition(players(6, 5), List.of(1, 2), new Random(seed)).get(1));
        }
        assertTrue(firstTeams.size() > 1, "players of the same bracket must not always land in the same team");
    }

    @Test
    void noPlayersStillListsEveryTeam() {
        Map<Integer, List<UUID>> teams = TeamPartitioner.partition(List.of(), List.of(1, 2), new Random(1));

        assertEquals(Map.of(1, List.of(), 2, List.of()), teams);
    }

    @Test
    void needsAtLeastOneTeam() {
        assertThrows(IllegalArgumentException.class, () -> TeamPartitioner.partition(players(2, 0), List.of(), new Random(1)));
    }
}
