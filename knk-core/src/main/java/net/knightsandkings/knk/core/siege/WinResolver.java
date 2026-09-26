package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who won (DESIGN §7.5). Winners are alliance groups, never team names.
 * <ol>
 *   <li>Instant-victory capture: the capturer's alliance.</li>
 *   <li>Time expired / not enough players: the alliance holding the instant-victory objectives. If
 *       they are held by several alliances, the one of those holding the most objectives overall;
 *       still tied, the tied alliance that contains a Defender team; otherwise a draw.</li>
 *   <li>No instant-victory objective in the scenario: the alliance holding the most objectives; ties
 *       as above.</li>
 *   <li>Elimination: the only alliance with members left. Admin stop / server restart: aborted.</li>
 * </ol>
 */
public final class WinResolver {

    /** How the result was decided (for announcements and match history). */
    public enum Decision {
        INSTANT_VICTORY_CAPTURE,
        INSTANT_VICTORY_HOLDER,
        MOST_OBJECTIVES,
        DEFENDER_ALLIANCE,
        LAST_ALLIANCE_STANDING,
        DRAW,
        ABORTED
    }

    /** @param winningAllianceGroup empty for a draw or an abort */
    public record Result(SiegeEndReason reason, OptionalInt winningAllianceGroup, Decision decision) {
        public boolean isDraw() {
            return decision == Decision.DRAW;
        }

        public boolean isAborted() {
            return decision == Decision.ABORTED;
        }

        /** The winner as the nullable Integer the match API takes. */
        public Integer winnerOrNull() {
            return winningAllianceGroup.isPresent() ? winningAllianceGroup.getAsInt() : null;
        }
    }

    private final AllianceResolver alliances;

    public WinResolver(AllianceResolver alliances) {
        this.alliances = alliances;
    }

    /**
     * @param alliancesWithMembers alliance groups that still have members (for elimination)
     */
    public Result resolve(SiegeEndReason reason, SiegeObjectiveBoard board, Set<Integer> alliancesWithMembers) {
        if (reason.isAbort()) {
            return new Result(reason, OptionalInt.empty(), Decision.ABORTED);
        }
        if (reason == SiegeEndReason.INSTANT_VICTORY) {
            Optional<ObjectiveState.CaptureEvent> capture = board.instantVictoryCapture();
            if (capture.isPresent()) {
                return win(reason, alliances.allianceOf(capture.get().newHolderTeamId()), Decision.INSTANT_VICTORY_CAPTURE);
            }
            // Defensive: no instant-victory capture recorded - fall through to the holding rules.
        }
        if (reason == SiegeEndReason.TEAM_ELIMINATED) {
            if (alliancesWithMembers.size() == 1) {
                return win(reason, alliancesWithMembers.iterator().next(), Decision.LAST_ALLIANCE_STANDING);
            }
            if (alliancesWithMembers.isEmpty()) {
                return new Result(reason, OptionalInt.empty(), Decision.DRAW);
            }
            // Defensive: more than one alliance still has members - use the holding rules.
        }
        return resolveByHoldings(reason, board);
    }

    private Result resolveByHoldings(SiegeEndReason reason, SiegeObjectiveBoard board) {
        Map<Integer, Integer> held = board.heldCountsByAlliance();
        Set<Integer> candidates;
        if (board.hasInstantVictoryObjectives()) {
            candidates = board.heldInstantVictoryCountsByAlliance().keySet();
            if (candidates.size() == 1) {
                return win(reason, candidates.iterator().next(), Decision.INSTANT_VICTORY_HOLDER);
            }
        } else {
            candidates = alliances.alliances();
        }

        int most = candidates.stream().mapToInt(a -> held.getOrDefault(a, 0)).max().orElse(0);
        List<Integer> top = candidates.stream().filter(a -> held.getOrDefault(a, 0) == most).toList();
        if (top.size() == 1) {
            return win(reason, top.get(0), Decision.MOST_OBJECTIVES);
        }
        Set<Integer> withDefender = new TreeSet<>();
        top.stream().filter(alliances::hasDefender).forEach(withDefender::add);
        if (withDefender.size() == 1) {
            return win(reason, withDefender.iterator().next(), Decision.DEFENDER_ALLIANCE);
        }
        return new Result(reason, OptionalInt.empty(), Decision.DRAW);
    }

    /**
     * Membership check after a leave or quit (DESIGN §6.8): only one alliance left means
     * {@link SiegeEndReason#TEAM_ELIMINATED}; otherwise fewer members than {@code playersMin} means
     * {@link SiegeEndReason#NOT_ENOUGH_PLAYERS}.
     */
    public Optional<SiegeEndReason> membershipEnd(Map<Integer, Integer> membersPerTeam, int playersMin) {
        if (alliances.alliancesWithMembers(membersPerTeam).size() <= 1) {
            return Optional.of(SiegeEndReason.TEAM_ELIMINATED);
        }
        int total = membersPerTeam.values().stream().mapToInt(c -> c == null ? 0 : Math.max(0, c)).sum();
        return total < playersMin ? Optional.of(SiegeEndReason.NOT_ENOUGH_PLAYERS) : Optional.empty();
    }

    private static Result win(SiegeEndReason reason, int allianceGroup, Decision decision) {
        return new Result(reason, OptionalInt.of(allianceGroup), decision);
    }
}
