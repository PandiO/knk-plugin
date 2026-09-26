package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeRotationEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.VoteTally.Draw;
import net.knightsandkings.knk.core.siege.VoteTally.DrawMethod;
import net.knightsandkings.knk.core.siege.VoteTally.VoteChoice;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static net.knightsandkings.knk.core.siege.SiegeTestData.entry;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.twoTeamScenario;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the vote draw (DESIGN §6.3) and the legacy regressions N1, N2, N17. */
class VoteTallyTest {

    private static final int A = 1;
    private static final int B = 2;
    private static final int C = 3;
    private static final int D = 4;

    private final List<KnkSiegeRotationEntry> rotation = List.of(
            entry(scenario(A)), entry(scenario(B)), entry(scenario(C)), entry(scenario(D)));

    private static KnkSiegeScenario scenario(int id) {
        return twoTeamScenario(id, 100 + id);
    }

    private static VoteTally tally(boolean allowRandom) {
        return new VoteTally(List.of(A, B), allowRandom);
    }

    private static void votes(VoteTally tally, int firstPlayer, int count, VoteChoice choice) {
        for (int i = 0; i < count; i++) tally.vote(player(firstPlayer + i), choice);
    }

    private Draw draw(VoteTally tally, long seed) {
        return tally.draw(new Random(seed), id -> true, rotation).orElseThrow();
    }

    @Test
    void n1HighestVotedScenarioWins() {
        VoteTally tally = tally(true);
        votes(tally, 1, 3, VoteChoice.scenario(A));
        votes(tally, 10, 1, VoteChoice.scenario(B));

        // v2 sorted ascending and took index 0 - the lowest-voted scenario. Every seed must pick A.
        for (long seed = 0; seed < 50; seed++) {
            Draw draw = draw(tally, seed);
            assertEquals(A, draw.scenarioId());
            assertEquals(DrawMethod.MOST_VOTES, draw.method());
            assertEquals(3, draw.scenarioVotes());
        }
    }

    @Test
    void n1TiesAreBrokenAtRandomWithinTheTiedSetOnly() {
        VoteTally tally = new VoteTally(List.of(A, B, C), true);
        votes(tally, 1, 2, VoteChoice.scenario(A));
        votes(tally, 10, 2, VoteChoice.scenario(B));
        votes(tally, 20, 1, VoteChoice.scenario(C));

        // One long-lived generator, as the runtime uses (fresh Randoms with consecutive seeds
        // give correlated first draws).
        Random random = new Random(42);
        Set<Integer> winners = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Draw draw = tally.draw(random, id -> true, rotation).orElseThrow();
            assertEquals(DrawMethod.TIE_BROKEN_AT_RANDOM, draw.method());
            winners.add(draw.scenarioId());
        }
        assertEquals(Set.of(A, B), winners, "C had fewer votes and must never win; A and B must both occur");
    }

    @Test
    void n2RandomVotesCountAndWinOnlyWhenStrictlyGreater() {
        VoteTally tally = tally(true);
        assertEquals(VoteResult.CAST, tally.vote(player(1), VoteChoice.random()));
        votes(tally, 2, 2, VoteChoice.random());
        votes(tally, 10, 2, VoteChoice.scenario(A));
        assertEquals(3, tally.randomVotes(), "v2 never recorded Random votes (N2)");

        // 3 Random > 2 for A: a random rotation scenario that is not a candidate.
        Random random = new Random(42);
        Set<Integer> drawn = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Draw draw = tally.draw(random, id -> true, rotation).orElseThrow();
            assertEquals(DrawMethod.RANDOM_VOTE, draw.method());
            assertEquals(3, draw.randomVotes());
            drawn.add(draw.scenarioId());
        }
        assertEquals(Set.of(C, D), drawn);
    }

    @Test
    void n2RandomDoesNotWinOnATie() {
        VoteTally tally = tally(true);
        votes(tally, 1, 2, VoteChoice.random());
        votes(tally, 10, 2, VoteChoice.scenario(A));

        for (long seed = 0; seed < 50; seed++) {
            Draw draw = draw(tally, seed);
            assertEquals(A, draw.scenarioId());
            assertEquals(DrawMethod.MOST_VOTES, draw.method());
        }
    }

    @Test
    void randomVoteFallsBackToTheCandidatesWhenTheRotationHasNothingElse() {
        VoteTally tally = tally(true);
        votes(tally, 1, 2, VoteChoice.random());

        Draw draw = tally.draw(new Random(1), id -> true, List.of(entry(scenario(A)), entry(scenario(B)))).orElseThrow();

        assertEquals(DrawMethod.RANDOM_VOTE, draw.method());
        assertTrue(Set.of(A, B).contains(draw.scenarioId()));
    }

    @Test
    void randomVoteIsRefusedWhenTheLobbyDisallowsIt() {
        VoteTally tally = tally(false);

        assertEquals(VoteResult.RANDOM_NOT_ALLOWED, tally.vote(player(1), VoteChoice.random()));
        assertEquals(0, tally.totalVotes());
    }

    @Test
    void n17VotingForYourCurrentChoiceWithdrawsIt() {
        VoteTally tally = tally(true);

        assertEquals(VoteResult.CAST, tally.vote(player(1), VoteChoice.scenario(A)));
        VoteResult result = assertDoesNotThrow(() -> tally.vote(player(1), VoteChoice.scenario(A)));

        assertEquals(VoteResult.REMOVED, result);
        assertTrue(tally.choiceOf(player(1)).isEmpty());
        assertEquals(0, tally.votesFor(A));
        assertEquals(0, tally.totalVotes());
        // And the member can vote again afterwards.
        assertEquals(VoteResult.CAST, tally.vote(player(1), VoteChoice.scenario(A)));
    }

    @Test
    void oneVotePerMemberMovesBetweenChoices() {
        VoteTally tally = tally(true);
        tally.vote(player(1), VoteChoice.scenario(A));

        assertEquals(VoteResult.CHANGED, tally.vote(player(1), VoteChoice.scenario(B)));
        assertEquals(0, tally.votesFor(A));
        assertEquals(1, tally.votesFor(B));
        assertEquals(VoteResult.CHANGED, tally.vote(player(1), VoteChoice.random()));
        assertEquals(0, tally.votesFor(B));
        assertEquals(1, tally.randomVotes());
        assertEquals(VoteResult.REMOVED, tally.vote(player(1), VoteChoice.random()));
        assertEquals(0, tally.randomVotes());
    }

    @Test
    void onlyCandidatesCanBeVotedFor() {
        VoteTally tally = tally(true);

        assertEquals(VoteResult.NOT_A_CANDIDATE, tally.vote(player(1), VoteChoice.scenario(C)));
    }

    @Test
    void leavingDropsTheVote() {
        VoteTally tally = tally(true);
        tally.vote(player(1), VoteChoice.scenario(A));

        assertTrue(tally.removeVoter(player(1)));
        assertEquals(0, tally.votesFor(A));
    }

    @Test
    void noVotesPicksRandomlyAmongTheCandidates() {
        VoteTally tally = tally(true);

        Random random = new Random(42);
        Set<Integer> drawn = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Draw draw = tally.draw(random, id -> true, rotation).orElseThrow();
            assertEquals(DrawMethod.NO_VOTES, draw.method());
            drawn.add(draw.scenarioId());
        }
        assertEquals(Set.of(A, B), drawn);
    }

    @Test
    void lockedCandidatesCannotWin() {
        VoteTally tally = tally(true);
        votes(tally, 1, 3, VoteChoice.scenario(A));
        votes(tally, 10, 1, VoteChoice.scenario(B));

        Draw draw = tally.draw(new Random(1), id -> id != A, rotation).orElseThrow();

        assertEquals(B, draw.scenarioId());
        assertEquals(DrawMethod.MOST_VOTES, draw.method());
    }

    @Test
    void allCandidatesLockedFallsBackToTheRestOfTheRotation() {
        VoteTally tally = tally(true);
        votes(tally, 1, 3, VoteChoice.scenario(A));

        Draw draw = tally.draw(new Random(1), id -> id == D, rotation).orElseThrow();
        assertEquals(D, draw.scenarioId());
        assertEquals(DrawMethod.CANDIDATES_UNAVAILABLE, draw.method());

        assertTrue(tally.draw(new Random(1), id -> false, rotation).isEmpty());
    }
}
