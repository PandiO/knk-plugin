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

/** Domain discovery (KNG-20): the discovery block of config.yml. */
class ConfigLoaderDiscoveryTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderDiscoveryTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            // KNG-22: the shipped config uses auth type apikey with an empty key, which the loader
            // refuses on purpose until the server owner sets one.
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigLoadsWithTheDesignDefaults() throws Exception {
        KnkConfig.DiscoveryConfig discovery = ConfigLoader.load(bundledConfig()).discovery();

        assertTrue(discovery.enabled());
        assertEquals(20, discovery.batchWindowTicks());
        assertEquals(12, discovery.maxRequestsPerMinute());
        assertEquals(Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), discovery.excludedGameModes());
        assertTrue(discovery.excludeSiegeParticipants());
        assertEquals("discovery-spool", discovery.spoolDirectory());
        assertEquals(60, discovery.replayIntervalSeconds());
        assertEquals(10, discovery.effectSpacingTicks());
        assertEquals("UI_TOAST_CHALLENGE_COMPLETE", discovery.effects().sound());
        assertEquals(0.8f, discovery.effects().soundVolume(), 0.0001);
        assertEquals("HAPPY_VILLAGER", discovery.effects().particle());
        assertEquals(30, discovery.effects().particleCount());
        assertTrue(discovery.effects().townFirework());
        assertEquals(KnkConfig.DiscoveryConfig.MessagesConfig.defaults(), discovery.messages());
    }

    @Test
    void aMissingSectionMeansOnWithDefaults() {
        assertEquals(KnkConfig.DiscoveryConfig.defaults(), ConfigLoader.loadDiscovery(null));
    }

    @Test
    void valuesCanBeOverridden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("discovery.enabled", false);
        yaml.set("discovery.excluded-game-modes", List.of("creative"));
        yaml.set("discovery.effects.spacing-ticks", 0);

        KnkConfig.DiscoveryConfig discovery = ConfigLoader.loadDiscovery(yaml.getConfigurationSection("discovery"));

        assertFalse(discovery.enabled());
        assertEquals(Set.of(GameMode.CREATIVE), discovery.excludedGameModes());
        assertEquals(0, discovery.effectSpacingTicks());
        assertEquals("HAPPY_VILLAGER", discovery.effects().particle(), "unset effect keys keep their defaults");
    }

    @Test
    void anUnknownGameModeOrABadIntervalIsAConfigError() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("discovery.excluded-game-modes", List.of("FLYING"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.loadDiscovery(yaml.getConfigurationSection("discovery")));

        YamlConfiguration bad = new YamlConfiguration();
        bad.set("discovery.batch-window-ticks", 0);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.loadDiscovery(bad.getConfigurationSection("discovery")).validate());
    }
}
