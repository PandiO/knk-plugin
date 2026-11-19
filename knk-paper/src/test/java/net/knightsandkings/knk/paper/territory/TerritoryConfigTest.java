package net.knightsandkings.knk.paper.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.territory.TerritorySettings;

/** production.enabled and production.territory.* (PandiO/knk-workspace#689): the bundled defaults and a missing block. */
class TerritoryConfigTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(TerritoryConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
    }

    @Test
    void theBundledConfig_hasTheFeatureOff_withTheDocumentedDefaults() {
        TerritorySettings settings = TerritoryConfig.read(bundled());

        assertFalse(settings.enabled());
        assertEquals(10, settings.refreshMinutes());
        assertTrue(settings.notifyPlayers());
        assertTrue(settings.report());
        assertFalse(settings.mapOutlines());
        assertEquals(500_000, settings.maxChunksPerWorld());
        assertEquals(TerritorySettings.DEFAULT_MESSAGES, settings.messages());
    }

    @Test
    void aServerWithoutTheBlock_isOff_withDefaults() {
        TerritorySettings settings = TerritoryConfig.read(new YamlConfiguration());

        assertFalse(settings.enabled());
        assertTrue(settings.report());
        assertEquals(TerritorySettings.DEFAULT_MESSAGES.get("enter-town"), settings.messages().get("enter-town"));
    }

    @Test
    void theValuesAreRead() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                production:
                  enabled: true
                  territory:
                    refresh-minutes: 0
                    notify-players: false
                    report: false
                    map-outlines: true
                    max-chunks-per-world: 1000
                    messages:
                      enter-town: "Hi {name}"
                """);

        TerritorySettings settings = TerritoryConfig.read(config);

        assertTrue(settings.enabled());
        assertEquals(0, settings.refreshMinutes());
        assertFalse(settings.notifyPlayers());
        assertFalse(settings.report());
        assertTrue(settings.mapOutlines());
        assertEquals(1000, settings.maxChunksPerWorld());
        assertEquals("Hi {name}", settings.messages().get("enter-town"));
    }
}
