package net.knightsandkings.knk.paper.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** {@code navigation.landmarks} (off-road phase 5.1, PandiO/knk-workspace#708): off unless set to true. */
class LandmarksConfigTest {

    @Test
    void theShippedConfigHasTheKeySwitchedOff() throws Exception {
        try (InputStream in = LandmarksConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(config.isBoolean(LandmarksConfig.KEY), "the key is in config.yml");
            assertFalse(LandmarksConfig.from(config).enabled());
        }
    }

    @Test
    void aMissingKeyOrConfigMeansOff() {
        assertFalse(LandmarksConfig.from(new YamlConfiguration()).enabled());
        assertFalse(LandmarksConfig.from(null).enabled());
        assertFalse(LandmarksConfig.defaults().enabled());
    }

    @Test
    void trueSwitchesItOn() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(LandmarksConfig.KEY, true);
        assertTrue(LandmarksConfig.from(config).enabled());
    }
}
