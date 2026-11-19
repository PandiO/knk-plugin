package net.knightsandkings.knk.core.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The API's TerritoryRules.Resolve on the game server (territory DESIGN § 2-3, A05). */
class TerritoryRulesTest {

    private static final TerritoryHolder PHOENIX = TerritoryHolder.town(1);
    private static final TerritoryHolder RIVERTOWN = TerritoryHolder.town(2);
    private static final TerritoryHolder AURELIA = TerritoryHolder.kingdom(7);

    @Test
    void oneHolder_ownsIt_noneIsWilderness() {
        assertEquals(PHOENIX, TerritoryRules.resolve(List.of(PHOENIX, PHOENIX), null).owner());
        assertTrue(TerritoryRules.resolve(List.of(), null).isWilderness());
    }

    @Test
    void twoTownsOfEqualRank_contestIt_unlessItWasAwardedToOne() {
        TerritoryVerdict contested = TerritoryRules.resolve(List.of(RIVERTOWN, PHOENIX), null);
        assertTrue(contested.isContested());
        assertNull(contested.owner());
        assertEquals(List.of(PHOENIX, RIVERTOWN), contested.contesting());

        TerritoryVerdict awarded = TerritoryRules.resolve(List.of(RIVERTOWN, PHOENIX), RIVERTOWN);
        assertEquals(RIVERTOWN, awarded.owner());
        assertFalse(awarded.isContested());

        assertTrue(TerritoryRules.resolve(List.of(RIVERTOWN, PHOENIX), AURELIA).isContested(), "an award to a third holder does nothing");
    }

    @Test
    void crownLand_outranksTowns_withoutAConflict() {
        TerritoryVerdict verdict = TerritoryRules.resolve(List.of(PHOENIX, AURELIA, RIVERTOWN), null);
        assertEquals(AURELIA, verdict.owner());
        assertFalse(TerritoryRules.isConflictPair(PHOENIX, AURELIA));
        assertTrue(TerritoryRules.isConflictPair(PHOENIX, RIVERTOWN));
        assertTrue(TerritoryRules.isConflictPair(AURELIA, TerritoryHolder.kingdom(8)));
        assertFalse(TerritoryRules.isConflictPair(PHOENIX, PHOENIX));
    }

    @Test
    void holderKinds_parseTheApiNames() {
        assertEquals(TerritoryHolder.Kind.TOWN, TerritoryHolder.Kind.parse("Town"));
        assertEquals(TerritoryHolder.Kind.KINGDOM, TerritoryHolder.Kind.parse(" kingdom "));
        assertNull(TerritoryHolder.Kind.parse("Province"));
        assertTrue(PHOENIX.compareTo(AURELIA) < 0);
    }
}
