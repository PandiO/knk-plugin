package net.knightsandkings.knk.paper.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.shop.display.DisplaySettings;

/** Display stalls P12 (PandiO/knk-workspace#250): {@code production.shops.displays} and the shipped defaults. */
class ShopDisplayConfigTest {

    @Test
    void theShippedConfigHasTheDefaults_andProductionOff() throws Exception {
        YamlConfiguration shipped;
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        DisplaySettings s = ShopDisplayConfig.read(shipped);
        assertFalse(s.enabled());
        assertEquals(DisplaySettings.defaults(false), s);
        assertTrue(shipped.isConfigurationSection(ShopDisplayConfig.SECTION));
    }

    @Test
    void readsTheKeys_andAMissingBlockIsTheDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production.enabled", true);
        assertEquals(DisplaySettings.defaults(true), ShopDisplayConfig.read(config));
        config.set("production.shops.displays.refresh-minutes", 2);
        config.set("production.shops.displays.selection-refresh-seconds", 60);
        config.set("production.shops.displays.best-seller-hours", 24);
        config.set("production.shops.displays.item-scale", 0.75);
        config.set("production.shops.displays.label", false);
        config.set("production.shops.displays.hints", false);
        assertEquals(new DisplaySettings(true, 2, 60, 24, 0.75, false, false), ShopDisplayConfig.read(config));
        assertFalse(ShopDisplayConfig.read(null).enabled());
    }
}
