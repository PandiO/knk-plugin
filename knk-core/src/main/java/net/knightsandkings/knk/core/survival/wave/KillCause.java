package net.knightsandkings.knk.core.survival.wave;

/**
 * Why an enemy died (DESIGN §3 step 4, §3c, §8). Only {@link #PLAYER} kills are earned: they pay rewards, match
 * currency and count in statistics. Every cause counts as dead for round completion.
 */
public enum KillCause {
    /** Killing blow by a living player (S32/S33: the killing blow decides, no assist share). */
    PLAYER,
    /** Stuck handling (§3c): respawn limit, round backstop. Not a kill: no reward, no currency, no statistics. */
    SYSTEM,
    /** Fall, lava, fire, suffocation and other environment damage. Pays nothing. */
    ENVIRONMENT,
    /** Killed by another NPC. Pays nothing. */
    NPC;

    /** True only for a player's killing blow. */
    public boolean earned() {
        return this == PLAYER;
    }
}
