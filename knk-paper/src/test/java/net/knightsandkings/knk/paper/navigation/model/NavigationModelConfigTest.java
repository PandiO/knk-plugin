package net.knightsandkings.knk.paper.navigation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** {@code navigation.destinations.stale-after-days} (destination engine E12, § 9; PandiO/knk-workspace#103). */
class NavigationModelConfigTest {

    @Test
    void theBundledConfigSpellsOutTheDefaultOfFourteenDays() throws Exception {
        try (InputStream in = NavigationModelConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

            assertEquals(14, config.getInt(NavigationModelConfig.STALE_AFTER_DAYS, -1), "the key is in config.yml");
            assertEquals(new NavigationModelConfig(14), NavigationModelConfig.from(config));
        }
    }

    @Test
    void aConfigWithoutTheKeyMeansFourteenDays() {
        assertEquals(Duration.ofDays(14), NavigationModelConfig.from(new YamlConfiguration()).staleAfter());
        assertEquals(NavigationModelConfig.defaults(), NavigationModelConfig.from(null));
    }

    @Test
    void theValueIsReadAndAtLeastOneDay() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(NavigationModelConfig.STALE_AFTER_DAYS, 30);
        assertEquals(30, NavigationModelConfig.from(config).staleAfterDays());

        config.set(NavigationModelConfig.STALE_AFTER_DAYS, 0);
        assertEquals(1, NavigationModelConfig.from(config).staleAfterDays());
    }
}
