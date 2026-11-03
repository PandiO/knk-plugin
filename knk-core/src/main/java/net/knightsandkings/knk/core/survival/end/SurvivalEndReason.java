package net.knightsandkings.knk.core.survival.end;

/**
 * Why a survival match ended (DESIGN §3a, §3 "Players, leave and disconnect", S37, S61, S62). Conceptual reasons,
 * distinct from the existing {@code SiegeEndReason} values; the adapter maps them for history and the result screen.
 */
public enum SurvivalEndReason {
    /** Every player still in the match died in the same active wave. Normal end: rewards settle. */
    TEAM_WIPE(false, true),
    /** NPCs captured an objective carrying the insta-lose flag (S17). Normal end: rewards settle. */
    INSTA_LOSE_OBJECTIVE_CAPTURED(false, true),
    /** NPCs hold every objective of the scenario at once and the setting is enabled (S19/S46). Normal end. */
    ALL_OBJECTIVES_CAPTURED(false, true),
    /** The last player left or disconnected (S37): nothing to settle. */
    NO_PLAYERS_LEFT(true, false),
    /** An admin stopped the match (S62): no permanent reward. */
    ADMIN_ABORT(true, false),
    /** Server/plugin restart (S61): the match is aborted, players and gates restored, no resume. */
    SERVER_RESTART(true, false),
    /** An explicit technical duration/resource limit (§3): an abort policy, never a secret win condition. */
    TECHNICAL_LIMIT(true, false);

    private final boolean abort;
    private final boolean settlesRewards;

    SurvivalEndReason(boolean abort, boolean settlesRewards) {
        this.abort = abort;
        this.settlesRewards = settlesRewards;
    }

    /** Technical end, not a gameplay outcome. */
    public boolean isAbort() {
        return abort;
    }

    /** Whether the per-kill permanent rewards (S32) are settled for the players still in the match. */
    public boolean settlesRewards() {
        return settlesRewards;
    }
}
