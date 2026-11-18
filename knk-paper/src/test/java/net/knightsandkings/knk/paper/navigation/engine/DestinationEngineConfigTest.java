package net.knightsandkings.knk.paper.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** {@code navigation.destination-engine} (phase 3, PandiO/knk-workspace#69, E17, A16): off unless set to true. */
class DestinationEngineConfigTest {

    @Test
    void theShippedConfigHasTheKeySwitchedOff() throws Exception {
        try (InputStream in = DestinationEngineConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(config.isBoolean(DestinationEngineConfig.KEY), "the key is in config.yml");
            assertFalse(DestinationEngineConfig.from(config).enabled());
        }
    }

    @Test
    void aMissingKeyOrConfigMeansOff() {
        assertFalse(DestinationEngineConfig.from(new YamlConfiguration()).enabled());
        assertFalse(DestinationEngineConfig.from(null).enabled());
        assertFalse(DestinationEngineConfig.defaults().enabled());
    }

    @Test
    void trueSwitchesItOn() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(DestinationEngineConfig.KEY, true);
        assertTrue(DestinationEngineConfig.from(config).enabled());
    }
}
