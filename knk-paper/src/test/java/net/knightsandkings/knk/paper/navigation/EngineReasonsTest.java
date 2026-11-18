package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Reason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Each client's words for the engine's reason codes (phase 3, PandiO/knk-workspace#69, E15, A19): {@code /navigate}
 * keeps today's messages, the compass its own for the siege codes.
 */
class EngineReasonsTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void theCommandSaysWhatItSaysToday() {
        assertEquals(plain(NavigationMessages.loading()), plain(EngineReasons.forCommand(InitialScopes.LOADING_REASON)));
        assertEquals(plain(NavigationMessages.frozen()), plain(EngineReasons.forCommand(InitialScopes.FROZEN_REASON)));
        assertEquals(plain(NavigationMessages.noPermission()), plain(EngineReasons.forCommand(InitialScopes.NO_PERMISSION_REASON)));
        assertEquals(plain(NavigationMessages.inSiege()), plain(EngineReasons.forCommand(InitialScopes.IN_SIEGE_REASON)));
        assertEquals(plain(CompassMessages.noMatchDestinations()), plain(EngineReasons.forCommand(InitialScopes.COMPASS_OFF_REASON)));
        assertEquals(plain(CompassMessages.noMatchDestinations()), plain(EngineReasons.forCommand(InitialScopes.MATCH_EMPTY_REASON)));
        // the texts are the scopes' default texts, so the engine's reasons and today's messages agree word for word
        assertEquals(plain(NavigationMessages.bad(InitialScopes.IN_SIEGE_REASON.text())), plain(EngineReasons.forCommand(InitialScopes.IN_SIEGE_REASON)));
    }

    @Test
    void theCompassKeepsItsOwnWordingForTheSiegeCodes() {
        assertEquals(plain(CompassMessages.waitForMatch()), plain(EngineReasons.forCompass(InitialScopes.IN_SIEGE_REASON)));
        assertEquals(plain(CompassMessages.disabledInSiege()), plain(EngineReasons.forCompass(InitialScopes.COMPASS_OFF_REASON)));
        assertEquals(plain(CompassMessages.disabledHere()), plain(EngineReasons.forCompass(InitialScopes.MATCH_EMPTY_REASON)));
        assertEquals(plain(CompassMessages.noPermission()), plain(EngineReasons.forCompass(InitialScopes.NO_PERMISSION_REASON)));
        assertEquals(plain(NavigationMessages.frozen()), plain(EngineReasons.forCompass(InitialScopes.FROZEN_REASON)));
    }

    @Test
    void anUnknownCodeShowsItsDefaultText() {
        Reason other = Reason.of("no-longer-listed", "Keep Gate is no longer part of this match.");
        assertEquals(plain(NavigationMessages.info(other.text())), plain(EngineReasons.forCommand(other)));
        assertEquals(plain(NavigationMessages.info(other.text())), plain(EngineReasons.forCompass(other)));
    }
}
