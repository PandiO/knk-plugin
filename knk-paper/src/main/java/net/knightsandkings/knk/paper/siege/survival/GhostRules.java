package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;

/**
 * When a dead defender comes back (DESIGN §3, §3b, S04, S41), Bukkit-free:
 * <ul>
 *   <li>killed during WAVE_ACTIVE → ghost until the intermission starts (the respawn moment);</li>
 *   <li>killed during the INTERMISSION → ghost until the next round starts;</li>
 *   <li>killed in PREPARING (before wave 1) → no ghost: the player just respawns;</li>
 *   <li>ENDING → no ghost (restore follows).</li>
 * </ul>
 * Deaths during the break never count toward a wipe ({@code SurvivalRounds.deathsCountTowardWipe()} covers that).
 */
public final class GhostRules {

    /** When a ghost is released. */
    public enum Release { INTERMISSION_START, NEXT_ROUND_START, NONE }

    private GhostRules() {
    }

    public static Release releaseFor(SurvivalSubstate deathSubstate) {
        return switch (deathSubstate) {
            case WAVE_ACTIVE -> Release.INTERMISSION_START;
            case INTERMISSION -> Release.NEXT_ROUND_START;
            case PREPARING, ENDING -> Release.NONE;
        };
    }

    /** Whether a death in {@code deathSubstate} makes the player a ghost at all. */
    public static boolean becomesGhost(SurvivalSubstate deathSubstate) {
        return releaseFor(deathSubstate) != Release.NONE;
    }

    /** Whether the transition into {@code entered} releases a ghost with {@code release}. */
    public static boolean releasedBy(Release release, SurvivalSubstate entered) {
        return switch (release) {
            case INTERMISSION_START -> entered == SurvivalSubstate.INTERMISSION;
            case NEXT_ROUND_START -> entered == SurvivalSubstate.WAVE_ACTIVE;
            case NONE -> false;
        };
    }

    /**
     * A player who respawns (clicks the button) after the moment that would have released them is simply alive
     * (DESIGN §3: "intermission respawns occur at its start"): a wave death respawning during the intermission, or
     * a break death respawning once the next round runs.
     */
    public static boolean respawnsAlive(Release release, SurvivalSubstate now) {
        return switch (release) {
            case INTERMISSION_START -> now != SurvivalSubstate.WAVE_ACTIVE;
            case NEXT_ROUND_START -> now == SurvivalSubstate.WAVE_ACTIVE || now == SurvivalSubstate.ENDING;
            case NONE -> true;
        };
    }
}
