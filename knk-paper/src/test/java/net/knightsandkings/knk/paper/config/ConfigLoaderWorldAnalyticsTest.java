package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** World analytics (KNG-34 link 7, IMPLEMENTATION_PLAN.md §5.1): the world-analytics block of config.yml. */
class ConfigLoaderWorldAnalyticsTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderWorldAnalyticsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigLoadsWithThePlanDefaults() throws Exception {
        KnkConfig.WorldAnalyticsConfig analytics = ConfigLoader.load(bundledConfig()).worldAnalytics();

        assertEquals(KnkConfig.WorldAnalyticsConfig.defaults(), analytics);
        assertEquals(new KnkConfig.WorldAnalyticsConfig(true, 10, 16, 300, true, true, true,
            Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), 12), analytics);
    }

    @Test
    void aMissingSection_meansOnWithDefaults_andTheSwitchesAreRead() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("world-analytics", null);
        assertEquals(KnkConfig.WorldAnalyticsConfig.defaults(), ConfigLoader.load(config).worldAnalytics());

        config.set("world-analytics.enabled", false);
        config.set("world-analytics.menu-funnels", false);
        config.set("world-analytics.cell-size", 32);
        config.set("world-analytics.excluded-game-modes", List.of("spectator"));
        KnkConfig.WorldAnalyticsConfig off = ConfigLoader.load(config).worldAnalytics();
        assertFalse(off.enabled());
        assertFalse(off.menuFunnels());
        assertEquals(32, off.cellSize());
        assertEquals(Set.of(GameMode.SPECTATOR), off.excludedGameModes());
        assertEquals(300, off.flushIntervalSeconds());
    }

    @Test
    void badValues_areConfigErrors() throws Exception {
        for (String[] bad : new String[][] {
            {"world-analytics.movement-sample-seconds", "5"},
            {"world-analytics.cell-size", "0"},
            {"world-analytics.flush-interval-seconds", "10"},
            {"world-analytics.max-pending-batches", "0"}}) {
            YamlConfiguration config = bundledConfig();
            config.set(bad[0], Integer.parseInt(bad[1]));
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config), bad[0]);
        }
        YamlConfiguration modes = bundledConfig();
        modes.set("world-analytics.excluded-game-modes", List.of("FLYING"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(modes));
    }

    @Test
    void theNineArgumentConstructor_keepsWorldAnalyticsDefaults() {
        KnkConfig config = new KnkConfig(null, null, null, null, KnkConfig.PrivateMessagesConfig.defaults(), null,
            KnkConfig.DiscoveryConfig.defaults(), KnkConfig.StatisticsConfig.defaults(), KnkConfig.TelemetryConfig.defaults());

        assertEquals(KnkConfig.WorldAnalyticsConfig.defaults(), config.worldAnalytics());
    }
}
