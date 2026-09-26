package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeRotationEntry;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

/**
 * The scenario vote of one matchmaking round (DESIGN §6.3; fixes legacy N1, N2, N17).
 * <ul>
 *   <li>One vote per member: a candidate scenario <b>or</b> Random. Voting for your current choice
 *       removes it (N17: un-voting is a normal state change, nothing to throw on). Voting for
 *       something else moves the vote.</li>
 *   <li>Draw: the <b>highest</b> vote count wins (N1: v2 sorted ascending and took the lowest);
 *       ties are broken uniformly at random within the tied set (v1 coin flip).</li>
 *   <li>Random (N2: dead in v2) wins only when its votes <b>strictly exceed</b> the best scenario's;
 *       it draws a scenario from the rotation, by weight, excluding the candidates.</li>
 *   <li>No votes at all: uniformly random among the candidates.</li>
 * </ul>
 * Membership is the caller's business: it only lets members vote and calls {@link #removeVoter}
 * when someone leaves. Not thread-safe (main thread).
 */
public final class VoteTally {

    /** A member's vote: a candidate scenario, or Random ({@code scenarioId == null}). */
    public record VoteChoice(Integer scenarioId) {
        private static final VoteChoice RANDOM = new VoteChoice(null);

        public static VoteChoice random() {
            return RANDOM;
        }

        public static VoteChoice scenario(int scenarioId) {
            return new VoteChoice(scenarioId);
        }

        public boolean isRandom() {
            return scenarioId == null;
        }
    }

    public enum VoteResult {
        /** First vote of this member. */
        CAST,
        /** The member moved their vote to another choice. */
        CHANGED,
        /** The member voted for their current choice: the vote is withdrawn. */
        REMOVED,
        NOT_A_CANDIDATE,
        RANDOM_NOT_ALLOWED,
        VOTING_CLOSED
    }

    public enum DrawMethod {
        /** One candidate had the most votes. */
        MOST_VOTES,
        /** Several candidates tied for the most votes; one was picked at random. */
        TIE_BROKEN_AT_RANDOM,
        /** Random had strictly more votes than any candidate. */
        RANDOM_VOTE,
        /** Nobody voted; a candidate was picked at random. */
        NO_VOTES,
        /** Every candidate became unavailable (locked by another lobby); drawn from the rest of the rotation. */
        CANDIDATES_UNAVAILABLE
    }

    /** The outcome of {@link #draw}. */
    public record Draw(int scenarioId, DrawMethod method, int scenarioVotes, int randomVotes) {}

    private final List<Integer> candidates;
    private final boolean allowRandomVote;
    private final Map<UUID, VoteChoice> votes = new LinkedHashMap<>();

    public VoteTally(List<Integer> candidateScenarioIds, boolean allowRandomVote) {
        this.candidates = List.copyOf(candidateScenarioIds);
        this.allowRandomVote = allowRandomVote;
    }

    public VoteResult vote(UUID voter, VoteChoice choice) {
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(choice, "choice");
        if (choice.isRandom() && !allowRandomVote) return VoteResult.RANDOM_NOT_ALLOWED;
        if (!choice.isRandom() && !candidates.contains(choice.scenarioId())) return VoteResult.NOT_A_CANDIDATE;

        VoteChoice current = votes.get(voter);
        if (choice.equals(current)) {
            votes.remove(voter);
            return VoteResult.REMOVED;
        }
        votes.put(voter, choice);
        return current == null ? VoteResult.CAST : VoteResult.CHANGED;
    }

    /** Drops a leaving member's vote. @return whether they had one */
    public boolean removeVoter(UUID voter) {
        return votes.remove(voter) != null;
    }

    public Optional<VoteChoice> choiceOf(UUID voter) {
        return Optional.ofNullable(votes.get(voter));
    }

    public int votesFor(int scenarioId) {
        return (int) votes.values().stream().filter(v -> !v.isRandom() && v.scenarioId() == scenarioId).count();
    }

    public int randomVotes() {
        return (int) votes.values().stream().filter(VoteChoice::isRandom).count();
    }

    public int totalVotes() {
        return votes.size();
    }

    public List<Integer> candidates() {
        return candidates;
    }

    public boolean allowRandomVote() {
        return allowRandomVote;
    }

    /**
     * Draws the scenario (DESIGN §6.3).
     *
     * @param available false for scenarios another lobby holds (runtime locks); they can't win
     * @param rotation  the lobby's rotation, the pool for Random and the fallback
     * @return empty when no scenario at all is available
     */
    public Optional<Draw> draw(RandomGenerator random, IntPredicate available, List<KnkSiegeRotationEntry> rotation) {
        List<Integer> open = candidates.stream().filter(available::test).toList();
        Map<Integer, Integer> tally = new HashMap<>();
        for (int id : open) tally.put(id, votesFor(id));
        int best = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int randomVoteCount = randomVotes();

        if (allowRandomVote && randomVoteCount > best) {
            List<KnkSiegeRotationEntry> pool = rotation.stream()
                    .filter(e -> !candidates.contains(e.scenario().id()) && available.test(e.scenario().id()))
                    .toList();
            Optional<Integer> picked = WeightedPicker.pick(pool, KnkSiegeRotationEntry::weight, random).map(e -> e.scenario().id());
            // The rotation has nothing besides the candidates: Random means "any of them".
            if (picked.isEmpty()) picked = WeightedPicker.pickUniform(open, random);
            return picked.map(id -> new Draw(id, DrawMethod.RANDOM_VOTE, tally.getOrDefault(id, 0), randomVoteCount));
        }

        if (open.isEmpty()) {
            List<KnkSiegeRotationEntry> pool = rotation.stream().filter(e -> available.test(e.scenario().id())).toList();
            return WeightedPicker.pick(pool, KnkSiegeRotationEntry::weight, random)
                    .map(e -> new Draw(e.scenario().id(), DrawMethod.CANDIDATES_UNAVAILABLE, 0, randomVoteCount));
        }

        if (best == 0) {
            int id = WeightedPicker.pickUniform(open, random).orElseThrow();
            return Optional.of(new Draw(id, DrawMethod.NO_VOTES, 0, randomVoteCount));
        }

        List<Integer> tied = open.stream().filter(id -> tally.get(id) == best).toList();
        if (tied.size() == 1) {
            return Optional.of(new Draw(tied.get(0), DrawMethod.MOST_VOTES, best, randomVoteCount));
        }
        int id = WeightedPicker.pickUniform(tied, random).orElseThrow();
        return Optional.of(new Draw(id, DrawMethod.TIE_BROKEN_AT_RANDOM, best, randomVoteCount));
    }
}
