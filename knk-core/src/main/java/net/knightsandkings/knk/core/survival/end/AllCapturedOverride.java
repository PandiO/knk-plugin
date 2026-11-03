package net.knightsandkings.knk.core.survival.end;

/** The survival lobby's override of "end when all objectives are captured" (S46): lobby → scenario, Inherit defers. */
public enum AllCapturedOverride {
    INHERIT, ON, OFF;

    /** Resolves this override against the scenario's setting. */
    public boolean resolve(boolean scenarioSetting) {
        return switch (this) {
            case INHERIT -> scenarioSetting;
            case ON -> true;
            case OFF -> false;
        };
    }
}
