package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Player statistics (KNG-34, IMPLEMENTATION_PLAN.md §5.1): the statistics block of config.yml. */
class ConfigLoaderStatisticsTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderStatisticsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigLoadsWithThePlanDefaults() throws Exception {
        KnkConfig.StatisticsConfig statistics = ConfigLoader.load(bundledConfig()).statistics();

        assertEquals(KnkConfig.StatisticsConfig.defaults(), statistics);
        assertTrue(statistics.enabled());
        assertEquals(60, statistics.flushIntervalSeconds());
        assertEquals(2000, statistics.maxBatchEntries());
        assertEquals("statistics-spool", statistics.spoolDirectory());
        assertEquals(60, statistics.replayIntervalSeconds());
        assertEquals(Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), statistics.excludedGameModes());
        assertEquals(new KnkConfig.StatisticsConfig.AfkConfig(true, 300, true, true, "&7[AFK]"), statistics.afk());
        assertEquals(new KnkConfig.StatisticsConfig.MovementConfig(true, 10.0), statistics.movement());
        assertTrue(statistics.fall().enabled());
    }

    @Test
    void aMissingSectionMeansOnWithDefaults() {
        assertEquals(KnkConfig.StatisticsConfig.defaults(), ConfigLoader.loadStatistics(null));
    }

    @Test
    void aConfigWithoutTheSectionStillLoads() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("statistics", null);
        assertEquals(KnkConfig.StatisticsConfig.defaults(), ConfigLoader.load(config).statistics());
    }

    @Test
    void valuesCanBeOverridden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("statistics.enabled", false);
        yaml.set("statistics.flush-interval-seconds", 30);
        yaml.set("statistics.max-batch-entries", 500);
        yaml.set("statistics.spool-directory", "stats");
        yaml.set("statistics.replay-interval-seconds", 120);
        yaml.set("statistics.excluded-game-modes", List.of("spectator"));
        yaml.set("statistics.afk.enabled", false);
        yaml.set("statistics.afk.idle-seconds", 600);
        yaml.set("statistics.afk.command-enabled", false);
        yaml.set("statistics.afk.tab-list-marker", false);
        yaml.set("statistics.afk.marker-text", "&8(away)");
        yaml.set("statistics.movement.enabled", false);
        yaml.set("statistics.movement.max-segment-blocks", 6.5);
        yaml.set("statistics.fall.enabled", false);

        KnkConfig.StatisticsConfig statistics = ConfigLoader.loadStatistics(yaml.getConfigurationSection("statistics"));
        statistics.validate();

        assertFalse(statistics.enabled());
        assertEquals(30, statistics.flushIntervalSeconds());
        assertEquals(500, statistics.maxBatchEntries());
        assertEquals("stats", statistics.spoolDirectory());
        assertEquals(120, statistics.replayIntervalSeconds());
        assertEquals(Set.of(GameMode.SPECTATOR), statistics.excludedGameModes());
        assertEquals(new KnkConfig.StatisticsConfig.AfkConfig(false, 600, false, false, "&8(away)"), statistics.afk());
        assertEquals(new KnkConfig.StatisticsConfig.MovementConfig(false, 6.5), statistics.movement());
        assertFalse(statistics.fall().enabled());
    }

    @Test
    void partialSubsectionsKeepTheirOtherDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("statistics.afk.idle-seconds", 120);

        KnkConfig.StatisticsConfig statistics = ConfigLoader.loadStatistics(yaml.getConfigurationSection("statistics"));

        assertEquals(120, statistics.afk().idleSeconds());
        assertTrue(statistics.afk().commandEnabled());
        assertEquals("&7[AFK]", statistics.afk().markerText());
        assertEquals(KnkConfig.StatisticsConfig.MovementConfig.defaults(), statistics.movement());
    }

    @Test
    void invalidValuesAreConfigErrors() {
        assertThrows(IllegalArgumentException.class, () -> load("statistics.flush-interval-seconds", 5).validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.max-batch-entries", 2001).validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.replay-interval-seconds", 1).validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.spool-directory", " ").validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.afk.idle-seconds", 10).validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.movement.max-segment-blocks", 0).validate());
        assertThrows(IllegalArgumentException.class, () -> load("statistics.excluded-game-modes", List.of("FLYING")));
    }

    private static KnkConfig.StatisticsConfig load(String key, Object value) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(key, value);
        return ConfigLoader.loadStatistics(yaml.getConfigurationSection("statistics"));
    }
}
