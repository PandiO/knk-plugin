package net.knightsandkings.knk.paper.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/** PandiO/knk-workspace#160: {@code menus.menu-item} in config.yml. */
class MenuOpenerConfigTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void theShippedConfigIsTheDefaults() throws Exception {
        YamlConfiguration yaml;
        try (var in = MenuOpenerConfigTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals(MenuOpenerSettings.defaults(), MenuOpenerConfig.read(yaml.getConfigurationSection("menus.menu-item"), LOG));
    }

    @Test
    void aMissingSectionIsTheDefaults() {
        assertEquals(MenuOpenerSettings.defaults(), MenuOpenerConfig.read(null, LOG));
    }

    @Test
    void everyKeyIsRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", false);
        yaml.set("give-to", "bedrock");
        yaml.set("give-on-join", false);
        yaml.set("keep-on-death", false);
        yaml.set("material", "clock");
        yaml.set("name", "&aM");
        yaml.set("lore", List.of("a", "b"));
        yaml.set("slot", 0);
        yaml.set("open-on", List.of("left-click", "bogus"));
        yaml.set("droppable", true);
        yaml.set("movable", true);
        MenuOpenerSettings s = MenuOpenerConfig.read(yaml, LOG);
        assertFalse(s.enabled());
        assertEquals(Audience.BEDROCK, s.audience());
        assertFalse(s.giveOnJoin());
        assertFalse(s.keepOnDeath());
        assertEquals("CLOCK", s.material());
        assertEquals("&aM", s.name());
        assertEquals(List.of("a", "b"), s.lore());
        assertEquals(0, s.slot());
        assertEquals(Set.of(Click.LEFT_CLICK), s.openOn());
        assertTrue(s.droppable());
        assertTrue(s.movable());
    }

    @Test
    void badValuesFallBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("give-to", "java");
        yaml.set("slot", 40);
        MenuOpenerSettings s = MenuOpenerConfig.read(yaml, LOG);
        assertEquals(Audience.ALL, s.audience());
        assertEquals(8, s.slot());
        assertEquals(EnumSet.allOf(Click.class), s.openOn());
    }
}
