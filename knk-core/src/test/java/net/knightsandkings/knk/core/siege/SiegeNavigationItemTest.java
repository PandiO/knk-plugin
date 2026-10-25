package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode;
import org.junit.jupiter.api.Test;

import static net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode.DO_NOT_GIVE;
import static net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode.GIVE;
import static net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode.INHERIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-234: lobby over scenario over the global siege configuration. */
class SiegeNavigationItemTest {

    @Test
    void whenEveryLevelInheritsTheGlobalSettingDecides() {
        assertTrue(SiegeNavigationItem.resolve(INHERIT, INHERIT, true));
        assertFalse(SiegeNavigationItem.resolve(INHERIT, INHERIT, false));
    }

    @Test
    void theScenarioOverridesTheGlobalSetting() {
        assertTrue(SiegeNavigationItem.resolve(INHERIT, GIVE, false));
        assertFalse(SiegeNavigationItem.resolve(INHERIT, DO_NOT_GIVE, true));
    }

    @Test
    void theLobbyOverridesTheScenarioAndTheGlobalSetting() {
        assertTrue(SiegeNavigationItem.resolve(GIVE, DO_NOT_GIVE, false));
        assertFalse(SiegeNavigationItem.resolve(DO_NOT_GIVE, GIVE, true));
    }

    @Test
    void aMissingChoiceInherits() {
        assertTrue(SiegeNavigationItem.resolve(null, null, true));
        assertTrue(SiegeNavigationItem.resolve(null, GIVE, false));
        assertFalse(SiegeNavigationItem.resolve(null, null, false));
    }

    @Test
    void theApiNamesParseAnyCaseAndFallBackToInherit() {
        assertEquals(GIVE, SiegeNavigationItemMode.fromApi("Give"));
        assertEquals(DO_NOT_GIVE, SiegeNavigationItemMode.fromApi("DoNotGive"));
        assertEquals(DO_NOT_GIVE, SiegeNavigationItemMode.fromApi("do_not_give"));
        assertEquals(INHERIT, SiegeNavigationItemMode.fromApi("Inherit"));
        assertEquals(INHERIT, SiegeNavigationItemMode.fromApi(null));
        assertEquals(INHERIT, SiegeNavigationItemMode.fromApi("sometimes"));
    }
}
