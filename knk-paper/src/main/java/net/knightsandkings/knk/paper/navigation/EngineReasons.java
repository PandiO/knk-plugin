package net.knightsandkings.knk.paper.navigation;

import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Reason;
import net.kyori.adventure.text.Component;

/**
 * The words each client shows for the destination engine's reasons (DESIGN E15, phase 3, PandiO/knk-workspace#69):
 * clients switch on the reason's code, never on its text. {@code /navigate} keeps today's messages (the scopes'
 * default texts, in today's colours); the compass keeps its own wording for the siege codes. An unknown code shows the
 * reason's default text. Pure: no Bukkit.
 */
public final class EngineReasons {

    private EngineReasons() {
    }

    /** What {@code /navigate} says for {@code reason}: today's message for each code. */
    public static Component forCommand(Reason reason) {
        if (reason == null) {
            return NavigationMessages.disabled();
        }
        return switch (reason.code()) {
            case InitialScopes.LOADING_CODE -> NavigationMessages.loading();
            case InitialScopes.FROZEN_CODE -> NavigationMessages.frozen();
            case InitialScopes.NO_PERMISSION_CODE -> NavigationMessages.noPermission();
            case InitialScopes.SIEGE_LOBBY_CODE -> NavigationMessages.inSiege();
            case InitialScopes.SIEGE_COMPASS_OFF_CODE, InitialScopes.SIEGE_MATCH_EMPTY_CODE -> CompassMessages.noMatchDestinations();
            default -> NavigationMessages.info(reason.text());
        };
    }

    /** What the compass says for {@code reason}: its own wording for the siege codes (E15), else the command's. */
    public static Component forCompass(Reason reason) {
        if (reason == null) {
            return CompassMessages.disabledHere();
        }
        return switch (reason.code()) {
            case InitialScopes.SIEGE_LOBBY_CODE -> CompassMessages.waitForMatch();
            case InitialScopes.SIEGE_COMPASS_OFF_CODE -> CompassMessages.disabledInSiege();
            case InitialScopes.SIEGE_MATCH_EMPTY_CODE -> CompassMessages.disabledHere();
            case InitialScopes.NO_PERMISSION_CODE -> CompassMessages.noPermission();
            default -> forCommand(reason);
        };
    }
}
