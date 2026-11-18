package net.knightsandkings.knk.paper.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertTrue(config.isDouble(LandmarksConfig.PREFERENCE_MAX_KEY), "phase 5.2 (#709): preference-max is there");
            assertEquals(4.0, LandmarksConfig.from(config).preferenceMax());
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

    @Test
    void preferenceMaxIsReadAndABadValueIsTheDefault() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(4.0, LandmarksConfig.from(config).preferenceMax(), "missing: 4.0");
        config.set(LandmarksConfig.PREFERENCE_MAX_KEY, 2.5);
        assertEquals(2.5, LandmarksConfig.from(config).preferenceMax());
        config.set(LandmarksConfig.PREFERENCE_MAX_KEY, 0.5);
        assertEquals(4.0, LandmarksConfig.from(config).preferenceMax(), "below 1.0: the default");
        assertEquals(4.0, new LandmarksConfig(true).preferenceMax());
    }
}
