package net.knightsandkings.knk.core.navigation.compass;

import net.knightsandkings.knk.core.navigation.compass.CompassItemRule.NameMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompassItemRuleTest {

    private final CompassItemRule rule = CompassItemRule.defaults();

    @Test
    void defaultsMatchTheNamedCompass() {
        assertTrue(rule.matches("COMPASS", "Navigation Compass"));
    }

    @Test
    void anotherMaterialOrNameDoesNotMatch() {
        assertFalse(rule.matches("RECOVERY_COMPASS", "Navigation Compass"));
        assertFalse(rule.matches("COMPASS", "Compass"));
        assertFalse(rule.matches("COMPASS", null));
        assertFalse(rule.matches(null, "Navigation Compass"));
    }

    @Test
    void colourCodesInTheItemNameAreIgnored() {
        assertTrue(rule.matches("COMPASS", "§6§lNavigation Compass"));
        assertTrue(rule.matches("COMPASS", "§x§f§f§a§a§0§0Navigation Compass"));
        assertTrue(rule.matches("COMPASS", "&6Navigation Compass"));
    }

    @Test
    void colourCodesInTheRuleAreIgnoredToo() {
        CompassItemRule coloured = new CompassItemRule(true, "COMPASS", "&6Navigation Compass", NameMatch.EXACT);
        assertTrue(coloured.matches("COMPASS", "§bNavigation Compass"));
    }

    @Test
    void caseDoesNotMatter() {
        assertTrue(rule.matches("compass", "navigation COMPASS"));
    }

    @Test
    void materialMayCarryANamespace() {
        CompassItemRule recovery = new CompassItemRule(true, "minecraft:recovery_compass", "", NameMatch.EXACT);
        assertEquals("RECOVERY_COMPASS", recovery.material());
        assertTrue(recovery.matches("RECOVERY_COMPASS", null));
        assertFalse(recovery.matches("COMPASS", null));
    }

    @Test
    void containsMatchesPartOfTheName() {
        CompassItemRule contains = new CompassItemRule(true, "COMPASS", "navigation", NameMatch.CONTAINS);
        assertTrue(contains.matches("COMPASS", "§aSiege Navigation Compass II"));
        assertFalse(contains.matches("COMPASS", "Plain compass"));
        assertFalse(rule.matches("COMPASS", "Siege Navigation Compass II"));
    }

    @Test
    void blankNameMeansMaterialOnly() {
        CompassItemRule any = new CompassItemRule(true, "COMPASS", "  ", NameMatch.EXACT);
        assertTrue(any.matches("COMPASS", null));
        assertTrue(any.matches("COMPASS", "Whatever"));
    }

    @Test
    void aDisabledRuleMatchesNothing() {
        assertFalse(new CompassItemRule(false, "COMPASS", "Navigation Compass", NameMatch.EXACT)
            .matches("COMPASS", "Navigation Compass"));
    }

    @Test
    void blankMaterialFallsBackToCompass() {
        assertEquals("COMPASS", new CompassItemRule(true, " ", "x", null).material());
    }

    @Test
    void nameMatchParsesAnyCase() {
        assertEquals(NameMatch.CONTAINS, NameMatch.parse("contains"));
        assertEquals(NameMatch.EXACT, NameMatch.parse(" Exact "));
        assertNull(NameMatch.parse("Equals"));
        assertNull(NameMatch.parse(null));
    }
}
