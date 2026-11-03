package net.knightsandkings.knk.core.survival.end;

/**
 * An objective as the end-condition evaluator sees it (DESIGN §3a, S17).
 *
 * @param id        the objective's id
 * @param instaLose the per-objective "ends the game when captured by the enemy" flag (Siege's InstantVictory)
 * @param npcHeld   whether the NPC team currently owns it
 */
public record SurvivalObjective(String id, boolean instaLose, boolean npcHeld) {

    public SurvivalObjective {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
    }
}
