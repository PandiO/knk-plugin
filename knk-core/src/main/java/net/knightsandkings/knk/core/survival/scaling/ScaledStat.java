package net.knightsandkings.knk.core.survival.scaling;

/**
 * The NPC base stats the round scaling raises (DESIGN §7, S21). Attack speed and walking speed are applied through
 * the shared NPC mechanics of NPC platform §5b (S66/S67): this package only produces the multipliers.
 */
public enum ScaledStat {
    HEALTH,
    WALKING_SPEED,
    MELEE_DAMAGE,
    RANGED_DAMAGE,
    ATTACK_SPEED
}
