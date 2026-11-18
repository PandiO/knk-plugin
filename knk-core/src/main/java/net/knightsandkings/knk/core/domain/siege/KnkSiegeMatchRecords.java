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

    /**
     * A participant's final stats, sent with {@code complete}.
     *
     * @param leftAt        null for a member present at the end; set for a member who left early and is reported
     *                      only for their stats (KNG-34 leaver fix - the server grants them no reward)
     * @param gateDamage    effective gate HP this member took off the match's gates: direct hits and their share of
     *                      fires they lit (alpha run 1, PandiO/knk-workspace#165; the server pays per full step)
     * @param activeSeconds this member's active (not AFK) seconds in the match (#166)
     * @param matchSeconds  their time in the match: first joining it to the end (or their last leave), time away
     *                      within the rejoin window included (#166; the server pays participation from the share)
     */
    public record ParticipantResult(
            int userId,
            int siegeTeamId,
            int kills,
            int deaths,
            int highestKillStreak,
            int captures,
            Instant leftAt,
            double gateDamage,
            long activeSeconds,
            long matchSeconds
    ) {
        public ParticipantResult {
            if (!(gateDamage > 0)) gateDamage = 0; // NaN and negatives
            if (Double.isInfinite(gateDamage)) gateDamage = Double.MAX_VALUE;
            matchSeconds = Math.max(0, matchSeconds);
            activeSeconds = Math.max(0, Math.min(activeSeconds, matchSeconds));
        }

        /** Without gate damage and activity (before #165/#166). */
        public ParticipantResult(int userId, int siegeTeamId, int kills, int deaths, int highestKillStreak, int captures, Instant leftAt) {
            this(userId, siegeTeamId, kills, deaths, highestKillStreak, captures, leftAt, 0, 0, 0);
        }

        /** A member present at the end. */
        public ParticipantResult(int userId, int siegeTeamId, int kills, int deaths, int highestKillStreak, int captures) {
            this(userId, siegeTeamId, kills, deaths, highestKillStreak, captures, null);
        }

        /** This result with the member's gate damage (#165) and activity (#166) in the match. */
        public ParticipantResult withContribution(double gateDamage, long activeSeconds, long matchSeconds) {
            return new ParticipantResult(userId, siegeTeamId, kills, deaths, highestKillStreak, captures, leftAt, gateDamage,
                    activeSeconds, matchSeconds);
        }
    }

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
     * @param gateDamage      the gate HP the server recorded for them (#165)
     * @param gateDamageSteps the full gate damage reward steps in it (paid per step)
     * @param participation   true when the participation reward was paid (active long enough, #166)
     * @param activeSeconds   their active seconds as recorded
     * @param matchSeconds    their match time as recorded
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
            List<net.knightsandkings.knk.core.domain.users.RewardMultiplier> coinMultipliers,
            int gateDamage,
            int gateDamageSteps,
            boolean participation,
            int activeSeconds,
            int matchSeconds
    ) {
        public ParticipantReward {
            coinMultipliers = coinMultipliers == null ? List.of() : List.copyOf(coinMultipliers);
        }

        /** Without the gate damage and participation figures (before #165/#166). */
        public ParticipantReward(int userId, boolean presentAtEnd, boolean won, int holdingCount, int captureCount,
                                 int coins, int experience, int gems, int baseCoins,
                                 List<net.knightsandkings.knk.core.domain.users.RewardMultiplier> coinMultipliers) {
            this(userId, presentAtEnd, won, holdingCount, captureCount, coins, experience, gems, baseCoins, coinMultipliers,
                    0, 0, false, 0, 0);
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
