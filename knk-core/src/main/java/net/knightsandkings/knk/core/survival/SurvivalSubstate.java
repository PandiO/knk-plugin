package net.knightsandkings.knk.core.survival;

/**
 * Survival substates inside a running Siege match (DESIGN §3): the design's names, independent of the existing
 * Siege phase enums.
 */
public enum SurvivalSubstate {
    /** Preparation countdown before wave 1. */
    PREPARING,
    /** A wave is active: enemies spawn and fight; deaths count toward a team wipe. */
    WAVE_ACTIVE,
    /** The break between rounds (S27): dead players respawn at its start; deaths never count toward a wipe (S41). */
    INTERMISSION,
    /** The match is over: spawns cancelled, results frozen. */
    ENDING
}
