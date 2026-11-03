package net.knightsandkings.knk.core.survival.end;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.SurvivalSubstate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * End conditions and reason precedence (DESIGN §3a, S17, S19, S26, S37, S46, S47). Evaluate after every state
 * change; the first enabled condition met ends the match once. When one tick meets several, the recorded reason
 * follows the precedence team wipe → insta-lose objective → all objectives captured. "No players left" comes
 * first of all: with nobody in the match there is nothing to wipe or settle.
 * <ul>
 *   <li>Team wipe: every player still in the match is dead during an active wave. Leavers are not players, so when
 *       the last living player leaves while ghosts remain, the match ends as a wipe. Deaths in the intermission never
 *       count (S41).</li>
 *   <li>Insta-lose: any objective with the flag is NPC-held. Always enabled per flag; no scenario switch (S46).</li>
 *   <li>All captured: the resolved setting is on and NPCs hold every objective at once. With recapture, this checks
 *       ownership at the moment of each capture, which the state-based check does naturally.</li>
 * </ul>
 * Technical aborts (admin stop, restart, limits) are not conditions: the adapter ends the match with that reason.
 */
public final class EndConditionEvaluator {
    private final EndConditionSettings settings;

    public EndConditionEvaluator(EndConditionSettings settings) {
        this.settings = settings;
    }

    public EndConditionSettings settings() {
        return settings;
    }

    /** The reason to end the match with, or empty while it continues. */
    public Optional<SurvivalEndReason> evaluate(EndConditionSnapshot snapshot) {
        if (snapshot.playersInMatch() == 0) {
            return Optional.of(SurvivalEndReason.NO_PLAYERS_LEFT);
        }
        if (snapshot.substate() == SurvivalSubstate.WAVE_ACTIVE && snapshot.livingPlayers() == 0) {
            return Optional.of(SurvivalEndReason.TEAM_WIPE);
        }
        boolean anyInstaLoseHeld = false;
        boolean allHeld = !snapshot.objectives().isEmpty();
        for (SurvivalObjective objective : snapshot.objectives()) {
            if (objective.instaLose() && objective.npcHeld()) anyInstaLoseHeld = true;
            if (!objective.npcHeld()) allHeld = false;
        }
        if (anyInstaLoseHeld) {
            return Optional.of(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED);
        }
        if (settings.allCapturedEnabled() && allHeld) {
            return Optional.of(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED);
        }
        return Optional.empty();
    }

    /**
     * Readiness (§3a): warns, without failing, when only a team wipe can end the game, and when all-captured is on
     * but the scenario has no objective (unreachable).
     */
    public List<ReadinessIssue> readiness(List<SurvivalObjective> objectives) {
        List<ReadinessIssue> issues = new ArrayList<>();
        boolean anyFlag = objectives.stream().anyMatch(SurvivalObjective::instaLose);
        boolean allCaptured = settings.allCapturedEnabled();
        if (!anyFlag && !allCaptured) {
            issues.add(ReadinessIssue.warning("end.wipe-only",
                "Only a team wipe can end the game: no objective has the insta-lose flag and "
                    + "\"end when all objectives are captured\" is off"));
        }
        if (allCaptured && objectives.isEmpty()) {
            issues.add(ReadinessIssue.warning("end.all-captured-unreachable",
                "\"End when all objectives are captured\" is on but the scenario has no objective"));
        }
        return List.copyOf(issues);
    }
}
