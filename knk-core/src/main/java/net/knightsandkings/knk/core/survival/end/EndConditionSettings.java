package net.knightsandkings.knk.core.survival.end;

/**
 * The configurable end conditions (DESIGN §3a, S19, S26, S46). Team wipe is always on and not a setting; the
 * per-objective insta-lose flags live on the objectives ({@link SurvivalObjective#instaLose()}).
 *
 * @param scenarioAllCaptured the scenario's "end when all objectives are captured" (default off)
 * @param lobbyOverride       the survival lobby's override (default {@link AllCapturedOverride#INHERIT})
 */
public record EndConditionSettings(boolean scenarioAllCaptured, AllCapturedOverride lobbyOverride) {

    public EndConditionSettings {
        if (lobbyOverride == null) throw new IllegalArgumentException("lobbyOverride is required");
    }

    /** S26/S46 defaults: all-captured off, lobby Inherit. */
    public static EndConditionSettings defaults() {
        return new EndConditionSettings(false, AllCapturedOverride.INHERIT);
    }

    /** The resolved, match-frozen value (§3 step 2): lobby → scenario. */
    public boolean allCapturedEnabled() {
        return lobbyOverride.resolve(scenarioAllCaptured);
    }
}
