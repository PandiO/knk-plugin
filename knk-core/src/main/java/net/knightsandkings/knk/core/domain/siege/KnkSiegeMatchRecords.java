package net.knightsandkings.knk.core.domain.siege;

import java.time.Instant;
import java.util.List;

/**
 * Request/response shapes for {@code SiegeMatchesCommandApi} (DESIGN §3.10, §7.6, §11.2).
 * <b>Provisional:</b> the match endpoints are Phase 6 and don't exist yet, so Phase 6 may reshape
 * these when it writes the API side. Gate snapshots (§8.2) are Phase 7 and not modelled here.
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

    /** One player's granted rewards, for the in-game breakdown message. */
    public record ParticipantReward(int userId, boolean won, int coins, int experience, int gems) {}

    /** The server's answer to {@code complete}; repeat calls return the stored result. */
    public record RewardSummary(long matchId, List<ParticipantReward> rewards) {
        public RewardSummary {
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }
    }
}
