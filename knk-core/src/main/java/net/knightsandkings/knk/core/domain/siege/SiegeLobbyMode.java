package net.knightsandkings.knk.core.domain.siege;

/** DESIGN D1. MVP runs {@link #CONTINUOUS} lobbies only; {@link #SCHEDULED} is Phase 10. */
public enum SiegeLobbyMode {
    CONTINUOUS,
    SCHEDULED;

    /**
     * Parses the API's string enum. Unknown values map to {@link #SCHEDULED} so a lobby in a mode
     * this plugin doesn't know is never auto-run.
     */
    public static SiegeLobbyMode fromApi(String value) {
        return SiegeEnums.parse(SiegeLobbyMode.class, value, SCHEDULED);
    }
}
