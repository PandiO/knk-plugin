package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;

/**
 * Maps between the plugin's live animation representation (AnimationState + a separate isJammed
 * flag - unchanged by item 5, decision 5.0-C leaves this an implementation choice) and the
 * backend's persisted GateDoorOpenState wire value (CLOSED/OPENING/OPEN/CLOSING/JAMMED, sent/
 * received as a plain string - see GateDoorsApi.updateState and GateDoorDto.openedState).
 */
public final class GateDoorOpenStateMapper {

    private GateDoorOpenStateMapper() {
    }

    /** The wire value to persist for a door's current live state. */
    public static String toWireValue(AnimationState state, boolean isJammed) {
        if (isJammed) {
            return "JAMMED";
        }
        return state != null ? state.name() : "CLOSED";
    }

    static AnimationState animationStateFromWireValue(String openedState) {
        // OPENING/CLOSING are only ever transient in-memory states in this plugin, never resumed
        // mid-animation from a DB read (mirrors the legacy IsOpened-boolean behavior, which only
        // ever distinguished OPEN vs. not-OPEN at load time) - and JAMMED isn't cleanly open or
        // closed, so it loads as CLOSED with isJammed set (see isJammedFromWireValue).
        return "OPEN".equals(openedState) ? AnimationState.OPEN : AnimationState.CLOSED;
    }

    static boolean isJammedFromWireValue(String openedState) {
        return "JAMMED".equals(openedState);
    }
}
