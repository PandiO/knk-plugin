package net.knightsandkings.knk.core.menu.opener;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#160: the menu item's settings. */
class MenuOpenerSettingsTest {

    @Test
    void defaultsGiveEveryoneAStarThatOpensOnAnyClick() {
        MenuOpenerSettings d = MenuOpenerSettings.defaults();
        assertTrue(d.wantedFor(false));
        assertTrue(d.wantedFor(true));
        assertEquals("NETHER_STAR", d.material());
        assertEquals(8, d.slot());
        assertEquals(EnumSet.allOf(Click.class), d.openOn());
        assertFalse(d.droppable());
        assertFalse(d.movable());
    }

    @Test
    void bedrockAudienceLeavesJavaPlayersOut() {
        MenuOpenerSettings s = new MenuOpenerSettings(true, Audience.BEDROCK, true, true, null, null, null, 8,
            Set.of(Click.LEFT_CLICK), false, false);
        assertTrue(s.wantedFor(true));
        assertFalse(s.wantedFor(false));
        assertFalse(MenuOpenerSettings.disabled().wantedFor(true));
    }

    @Test
    void badValuesFallBackToTheDefaults() {
        MenuOpenerSettings s = new MenuOpenerSettings(true, null, true, true, " ", "", null, 99, null, false, false);
        assertEquals(Audience.ALL, s.audience());
        assertEquals("NETHER_STAR", s.material());
        assertEquals(MenuOpenerSettings.DEFAULT_NAME, s.name());
        assertEquals(8, s.slot());
        assertTrue(s.openOn().isEmpty());
        assertEquals("CLOCK", new MenuOpenerSettings(true, null, true, true, "clock", null, null, 0, null, false, false).material());
    }

    @Test
    void parsing() {
        assertEquals(Audience.BEDROCK, Audience.parse(" Bedrock "));
        assertNull(Audience.parse("java"));
        assertEquals(Click.INVENTORY_CLICK, Click.parse("inventory-click"));
        assertEquals(Click.RIGHT_CLICK, Click.parse("right_click"));
        assertNull(Click.parse("middle"));
    }

    @Test
    void theFingerprintChangesWithWhatThePlayerSees() {
        MenuOpenerSettings a = MenuOpenerSettings.defaults();
        MenuOpenerSettings b = new MenuOpenerSettings(true, Audience.ALL, true, true, "NETHER_STAR", a.name(),
            List.of("other"), 8, a.openOn(), false, false);
        MenuOpenerSettings c = new MenuOpenerSettings(false, Audience.BEDROCK, false, false, "NETHER_STAR", a.name(),
            a.lore(), 3, Set.of(), true, true);
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertEquals(a.fingerprint(), c.fingerprint(), "slot and rules don't change the item itself");
    }
}
