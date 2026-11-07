package net.knightsandkings.knk.core.survival.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchItemRulesTest {

    @Test
    void onlyMembersOfTheItemsMatchPickItUp() {
        assertEquals(MatchItemRules.Decision.ALLOW, MatchItemRules.pickup("m1", "m1"));
        assertEquals(MatchItemRules.Decision.DENY, MatchItemRules.pickup("m1", "m2"));
        assertEquals(MatchItemRules.Decision.DENY, MatchItemRules.pickup("m1", null));
        assertEquals(MatchItemRules.Decision.NOT_A_MATCH_ITEM, MatchItemRules.pickup(null, "m1"));
        assertEquals(MatchItemRules.Decision.NOT_A_MATCH_ITEM, MatchItemRules.pickup(" ", "m1"));
    }

    @Test
    void nonPlayersNeverGetMatchItems() {
        assertTrue(MatchItemRules.deniedToNonPlayers("m1"));
        assertFalse(MatchItemRules.deniedToNonPlayers(null));
        assertFalse(MatchItemRules.deniedToNonPlayers(""));
    }
}
