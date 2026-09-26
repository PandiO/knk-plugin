package net.knightsandkings.knk.core.domain.siege;

import java.time.Instant;
import java.util.List;

/**
 * Request/response shapes for {@code SiegeMatchesCommandApi} (DESIGN §3.10, §7.6, §11.2), matching
 * knk-web-api's {@code /api/siege-matches} endpoints (siege Phase 6). Gate snapshots (§8.2) are
 * Phase 7 and not modelled here.
 */
public final class KnkSiegeMatchRecords {
    private KnkSiegeMatchRecords() {}

    /** A participant and the team they were split into, sent when the match starts. */
    public record Participant(int userId, int siegeTeamId) {}

    /** A participant's final stats, sent with {@code complete}. */
    public record ParticipantResult(
            int userId,
            int siegeTeamId,
            int kills,
            int deaths,
            int highestKillStreak,
            int captures
    ) {}

    /**
     * One capture (or, with no capture, the final holder) of an objective. With recapture enabled an
     * objective can have several entries; the server takes the last one as the final holder.
     *
     * @param capturedByUserId null when the objective was never captured
     * @param capturedAt       null when the objective was never captured
     */
    public record ObjectiveResult(int objectiveId, int finalHolderTeamId, Integer capturedByUserId, Instant capturedAt) {}

    /**
     * What the plugin reports at the end of a match; the server computes and grants the rewards.
     *
     * @param winningAllianceGroup null for a draw
     */
    public record Completion(
            SiegeEndReason endReason,
            Integer winningAllianceGroup,
            List<ParticipantResult> participants,
            List<ObjectiveResult> objectives
    ) {
        public Completion {
            participants = participants == null ? List.of() : List.copyOf(participants);
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
        }
    }

    /**
     * One player's granted rewards, for the in-game breakdown message (DESIGN §7.6).
     *
     * @param presentAtEnd  false for participants who left before the end (they get nothing)
     * @param holdingCount  objectives their team holds at the end and did not hold at the start
     * @param captureCount  distinct objectives they captured
     * @param coins           granted coins, after {@code coinMultipliers}
     * @param baseCoins       the coins before the multipliers (DESIGN §7.6 amounts)
     * @param coinMultipliers the personal and rank (premium) multipliers the server applied, KNG-16
     *                        style (smoke test 2026-09-26); empty when none or on a repeat call
     */
    public record ParticipantReward(
            int userId,
            boolean presentAtEnd,
            boolean won,
            int holdingCount,
            int captureCount,
            int coins,
            int experience,
            int gems,
            int baseCoins,
            List<net.knightsandkings.knk.core.domain.users.RewardMultiplier> coinMultipliers
    ) {
        public ParticipantReward {
            coinMultipliers = coinMultipliers == null ? List.of() : List.copyOf(coinMultipliers);
        }

        /** Without a multiplier breakdown: the base is the granted amount. */
        public ParticipantReward(int userId, boolean presentAtEnd, boolean won, int holdingCount, int captureCount,
                                 int coins, int experience, int gems) {
            this(userId, presentAtEnd, won, holdingCount, captureCount, coins, experience, gems, coins, List.of());
        }

        /** True when this player got anything. */
        public boolean hasRewards() {
            return coins > 0 || experience > 0 || gems > 0;
        }
    }

    /**
     * The server's answer to {@code complete}; repeat calls return the stored result.
     *
     * @param alreadyCompleted true when the server found the match already completed (a retry or a
     *                         spooled replay) and granted nothing this time
     */
    public record RewardSummary(long matchId, boolean alreadyCompleted, List<ParticipantReward> rewards) {
        public RewardSummary {
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }

        public RewardSummary(long matchId, List<ParticipantReward> rewards) {
            this(matchId, false, rewards);
        }
    }
}
