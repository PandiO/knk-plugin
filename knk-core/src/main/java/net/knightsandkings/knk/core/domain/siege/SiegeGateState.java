package net.knightsandkings.knk.core.domain.siege;

/**
 * Resting gate state used by siege config (the API's {@code GateDoorOpenState}, limited to
 * OPEN/CLOSED by scenario validation): a selected gate's {@code initialState} and an objective's
 * {@code gateStateOnCapture}.
 */
public enum SiegeGateState {
    OPEN,
    CLOSED;

    public static SiegeGateState fromApi(String value, SiegeGateState fallback) {
        return SiegeEnums.parse(SiegeGateState.class, value, fallback);
    }
}
