package net.knightsandkings.knk.core.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;
import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings.Action;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/** A29 (PandiO/knk-workspace#871): the game settings page's menu item section becomes the plugin's settings. */
class MenuItemApiSettingsTest {

    private final MenuItemSituations situations = new MenuItemSituations();
    private final List<String> warnings = new ArrayList<>();

    private static Action open(String key) {
        return new Action("OpenMenu", key, null);
    }

    @Test
    void theLookRulesClicksAndOverridesArrive() {
        Map<String, Map<String, Action>> overrides = new LinkedHashMap<>();
        overrides.put("siege.match", Map.of("LEFT_CLICK", open("siege.information"), "SHIFT_RIGHT_CLICK", open("siege.shop")));
        KnkMenuItemSettings api = new KnkMenuItemSettings(true, "bedrock", false, true, "CLOCK", "&aMenu",
            List.of("&7line"), 4, true, false,
            Map.of("LEFT_CLICK", open("main"), "RIGHT_CLICK", new Action("RunCommand", null, "/warp"),
                "INVENTORY_CLICK", new Action("None", null, null)),
            overrides);

        MenuOpenerSettings s = MenuItemApiSettings.toSettings(api, situations, warnings::add);

        assertEquals(Audience.BEDROCK, s.audience());
        assertFalse(s.giveOnJoin());
        assertEquals("CLOCK", s.material());
        assertEquals("&aMenu", s.name());
        assertEquals(List.of("&7line"), s.lore());
        assertEquals(4, s.slot());
        assertTrue(s.droppable());
        assertEquals(MenuItemAction.openMenu("main"), s.clicks().get(Click.LEFT_CLICK));
        assertEquals(MenuItemAction.runCommand("warp"), s.clicks().get(Click.RIGHT_CLICK));
        assertEquals(EnumSet.of(Click.LEFT_CLICK, Click.RIGHT_CLICK), s.openOn(), "the clicks that do something");
        assertFalse(s.opensOn(Click.INVENTORY_CLICK));
        assertEquals(MenuItemAction.openMenu("siege.shop"), s.overrides().get("siege.match").get(Click.SHIFT_RIGHT_CLICK));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void whatThisPluginDoesNotKnowIsLeftOutWithAWarning() {
        Map<String, Map<String, Action>> overrides = new LinkedHashMap<>();
        overrides.put("arena.duel", Map.of("LEFT_CLICK", open("arena")));
        KnkMenuItemSettings api = new KnkMenuItemSettings(true, "everyone", true, true, null, null, null, 99, false,
            false, Map.of("MIDDLE_CLICK", open("main"), "LEFT_CLICK", new Action("Teleport", null, null)), overrides);

        MenuOpenerSettings s = MenuItemApiSettings.toSettings(api, situations, warnings::add);

        assertEquals(Audience.ALL, s.audience());
        assertEquals(MenuOpenerSettings.DEFAULT_SLOT, s.slot());
        assertTrue(s.clicks().get(Click.LEFT_CLICK).isNone());
        assertFalse(s.clicks().containsKey(Click.RIGHT_CLICK));
        assertTrue(s.overrides().isEmpty());
        assertEquals(5, warnings.size(), warnings.toString());
        assertNull(MenuItemApiSettings.toSettings(null, situations, warnings::add));
    }

    @Test
    void theFingerprintIgnoresClicks_SoAClickChangeNeverReplacesTheItem() {
        KnkMenuItemSettings a = new KnkMenuItemSettings(true, "all", true, true, "NETHER_STAR", "n", List.of(), 8, false,
            false, Map.of("LEFT_CLICK", open("main")), Map.of());
        KnkMenuItemSettings b = new KnkMenuItemSettings(true, "all", true, true, "NETHER_STAR", "n", List.of(), 8, false,
            false, Map.of("LEFT_CLICK", open("siege.overview")), Map.of());
        assertEquals(MenuItemApiSettings.toSettings(a, situations, null).fingerprint(),
            MenuItemApiSettings.toSettings(b, situations, null).fingerprint());
    }
}
