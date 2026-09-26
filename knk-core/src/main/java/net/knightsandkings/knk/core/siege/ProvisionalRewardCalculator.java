package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeRewards;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The reward breakdown a participant can expect (DESIGN §7.6), for the in-game end-of-match message
 * <b>until Phase 6</b>: rewards are computed and granted server-side by
 * {@code POST /api/siege-matches/{id}/complete}, whose answer replaces this. Nothing here grants
 * anything.
 * <ul>
 *   <li>Win: the win amounts when the participant's alliance won (nothing for a draw or an abort).</li>
 *   <li>Holding: per objective the participant's team holds at the end and did not hold at the start.</li>
 *   <li>Capture: per objective the participant personally captured, at most once per objective.</li>
 * </ul>
 */
public final class ProvisionalRewardCalculator {
    private ProvisionalRewardCalculator() {}

    public record Breakdown(boolean won, int holdingCount, int captureCount, int coins, int experience, int gems) {
        public static final Breakdown NONE = new Breakdown(false, 0, 0, 0, 0, 0);

        public boolean isEmpty() {
            return coins == 0 && experience == 0 && gems == 0;
        }
    }

    public static Breakdown forParticipant(
            KnkSiegeRewards rewards,
            WinResolver.Result result,
            AllianceResolver alliances,
            SiegeObjectiveBoard board,
            UUID playerId,
            int teamId
    ) {
        if (result.isAborted()) return Breakdown.NONE;

        boolean won = result.winningAllianceGroup().isPresent()
                && alliances.knows(teamId)
                && alliances.allianceOf(teamId) == result.winningAllianceGroup().getAsInt();

        int holding = 0;
        Set<Integer> captured = new HashSet<>();
        for (ObjectiveState state : board.objectives()) {
            if (state.holderTeamId() == teamId && state.initialHolderTeamId() != teamId) holding++;
            state.captures().stream()
                    .filter(c -> playerId.equals(c.capturerId()))
                    .forEach(c -> captured.add(c.objectiveId()));
        }
        int captures = captured.size();

        int coins = (won ? rewards.coinWin() : 0) + holding * rewards.coinHolding() + captures * rewards.coinCapture();
        int experience = (won ? rewards.expWin() : 0) + holding * rewards.expHolding() + captures * rewards.expCapture();
        int gems = won ? rewards.gemWin() : 0;
        return new Breakdown(won, holding, captures, coins, experience, gems);
    }
}
