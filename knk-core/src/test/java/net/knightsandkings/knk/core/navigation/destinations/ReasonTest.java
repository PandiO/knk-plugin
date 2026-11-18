package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Reasons are a code plus a default text (DESIGN § 3, E15): the codes of the initial scope table, today's
 * {@code /navigate} texts as the defaults, and the code rules a client relies on.
 */
class ReasonTest {

    @Test
    void theInitialTableHasTheDesignsCodesAndTodaysTexts() {
        Map<Reason, List<String>> expected = Map.of(
            InitialScopes.LOADING_REASON, List.of("loading", "Your account is still loading - try again in a moment."),
            InitialScopes.FROZEN_REASON, List.of("frozen", "You can't navigate while frozen."),
            InitialScopes.NO_PERMISSION_REASON, List.of("no-permission", "You don't have permission to use /navigate."),
            InitialScopes.COMPASS_OFF_REASON, List.of("siege-compass-off", "This match has no destinations to navigate to."),
            InitialScopes.MATCH_EMPTY_REASON, List.of("siege-match-empty", "This match has no destinations to navigate to."),
            InitialScopes.IN_SIEGE_REASON, List.of("siege-lobby", "You can't navigate while you are in a siege."));
        expected.forEach((reason, codeAndText) -> {
            assertEquals(codeAndText.get(0), reason.code());
            assertEquals(codeAndText.get(1), reason.text());
        });
        Set<String> codes = new HashSet<>();
        expected.keySet().forEach(r -> assertTrue(codes.add(r.code()), "codes are unique: " + r.code()));
        assertFalse(codes.contains(Reason.NO_LONGER_LISTED), "the engine's own code is not a scope's");
    }

    @Test
    void theSameTextUnderTwoCodesStaysTwoReasons() {
        assertEquals(InitialScopes.COMPASS_OFF_REASON.text(), InitialScopes.MATCH_EMPTY_REASON.text());
        assertFalse(InitialScopes.COMPASS_OFF_REASON.equals(InitialScopes.MATCH_EMPTY_REASON),
            "the compass words 'compass off' and 'nothing in this match' differently, so the codes differ");
        assertTrue(InitialScopes.COMPASS_OFF_REASON.is("siege-compass-off"));
        assertFalse(InitialScopes.COMPASS_OFF_REASON.is("siege-match-empty"));
    }

    @Test
    void aCodeIsLowerCaseWordsJoinedByHyphensAndATextIsNeeded() {
        assertEquals("a-b-2", Reason.of("a-b-2", "t").code());
        assertEquals("frozen: You can't navigate while frozen.", InitialScopes.FROZEN_REASON.toString());
        assertThrows(IllegalArgumentException.class, () -> Reason.of("Siege-Lobby", "t"));
        assertThrows(IllegalArgumentException.class, () -> Reason.of("siege lobby", "t"));
        assertThrows(IllegalArgumentException.class, () -> Reason.of("-x", "t"));
        assertThrows(IllegalArgumentException.class, () -> Reason.of("", "t"));
        assertThrows(IllegalArgumentException.class, () -> Reason.of("x", " "));
        assertThrows(NullPointerException.class, () -> Reason.of(null, "t"));
        assertThrows(NullPointerException.class, () -> Reason.of("x", null));
    }

    @Test
    void aListWithoutAReasonHasNoCodeOrText() {
        DestinationList list = DestinationList.empty("w", "the world", null, 0);
        assertEquals(null, list.reasonCode());
        assertEquals(null, list.reasonText());
        DestinationList refused = DestinationList.empty("f", "the world", InitialScopes.FROZEN_REASON, 0);
        assertEquals("frozen", refused.reasonCode());
        assertEquals("You can't navigate while frozen.", refused.reasonText());
    }
}
