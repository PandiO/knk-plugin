package net.knightsandkings.knk.core.navigation.compass;

/**
 * What one click with the navigation compass does (KNG-234). A plain right-click cycles to the next target
 * and starts navigating there. Shift-click is the on/off switch: it stops a running navigation, and when
 * none is running it brings back the last target (or starts the first one, when the player never chose).
 */
public final class CompassUse {
    private CompassUse() {
    }

    public enum Action {
        /** Select the next target and navigate to it. */
        CYCLE,
        /** End the running navigation. */
        STOP,
        /** Navigate to the target chosen last. */
        RESUME
    }

    public static Action onClick(boolean sneaking, boolean navigating, boolean hasLastTarget) {
        if (!sneaking) {
            return Action.CYCLE;
        }
        if (navigating) {
            return Action.STOP;
        }
        return hasLastTarget ? Action.RESUME : Action.CYCLE;
    }
}
