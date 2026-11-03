package net.knightsandkings.knk.core.survival.end;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;

import java.util.List;

/**
 * The state the end conditions are evaluated against, after each state change (DESIGN §3a "first condition wins").
 *
 * @param substate       the survival substate; a team wipe is only possible in {@link SurvivalSubstate#WAVE_ACTIVE} (S41)
 * @param playersInMatch players still in the match (leavers excluded, ghosts included; S37)
 * @param livingPlayers  players in the match that are alive (not ghosts)
 * @param objectives     every objective of the scenario with its flag and current owner
 */
public record EndConditionSnapshot(SurvivalSubstate substate, int playersInMatch, int livingPlayers,
                                   List<SurvivalObjective> objectives) {

    public EndConditionSnapshot {
        if (substate == null) throw new IllegalArgumentException("substate is required");
        if (playersInMatch < 0 || livingPlayers < 0) throw new IllegalArgumentException("player counts must be >= 0");
        if (livingPlayers > playersInMatch) throw new IllegalArgumentException("livingPlayers > playersInMatch");
        objectives = List.copyOf(objectives);
    }
}
