package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.core.realtime.ReconnectBackoff;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#178: the realtime block of config.yml and the hub URL it resolves to. */
class RealtimeSettingsTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = RealtimeSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void theBundledConfigIsOffWithTheDesignDefaults() throws Exception {
        RealtimeSettings settings = RealtimeSettings.fromConfig(bundledConfig());

        assertEquals(RealtimeSettings.defaults(), settings);
        assertFalse(settings.enabled());
        assertEquals("localhost", settings.serverId());
        assertEquals(ReconnectBackoff.DEFAULT_STEPS, settings.backoff());
        assertEquals(0.2, settings.jitter());
    }

    @Test
    void aMissingSectionMeansOff() {
        assertEquals(RealtimeSettings.defaults(), RealtimeSettings.fromConfig(new YamlConfiguration()));
        assertEquals(RealtimeSettings.defaults(), RealtimeSettings.fromConfig(null));
    }

    @Test
    void valuesAreRead() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.enabled", true);
        config.set("realtime.server-id", "survival-1");
        config.set("realtime.hub-url", " http://api:5000/api/hubs/game ");
        config.set("realtime.backoff-seconds", List.of(0.5, 3, -1, "x"));
        config.set("realtime.backoff-jitter", 5.0);

        RealtimeSettings settings = RealtimeSettings.fromConfig(config);

        assertTrue(settings.enabled());
        assertEquals("survival-1", settings.serverId());
        assertEquals("http://api:5000/api/hubs/game", settings.hubUrl());
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofSeconds(3)), settings.backoff());
        assertEquals(1.0, settings.jitter());
    }

    @Test
    void fallbackPollMultiplierDefaultsToFour_AndIsClamped() throws Exception {
        assertEquals(4, RealtimeSettings.fromConfig(bundledConfig()).fallbackPollMultiplier());
        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.fallback-poll-multiplier", 0);
        assertEquals(1, RealtimeSettings.fromConfig(config).fallbackPollMultiplier());
        config.set("realtime.fallback-poll-multiplier", 1000);
        assertEquals(RealtimeSettings.MAX_POLL_MULTIPLIER, RealtimeSettings.fromConfig(config).fallbackPollMultiplier());
        config.set("realtime.fallback-poll-multiplier", 6);
        assertEquals(6, RealtimeSettings.fromConfig(config).fallbackPollMultiplier());
    }

    @Test
    void anInvalidServerIdFallsBackToLocalhost() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.server-id", "has spaces");
        assertEquals("localhost", RealtimeSettings.fromConfig(config).serverId());
        assertFalse(RealtimeSettings.validServerId(""));
        assertFalse(RealtimeSettings.validServerId("x".repeat(65)));
        assertTrue(RealtimeSettings.validServerId("a.b_c-1"));
    }

    @Test
    void hubUrlIsDerivedFromTheApiBaseUrl() {
        RealtimeSettings defaults = RealtimeSettings.defaults();
        assertEquals("http://api:5000/api/hubs/game", defaults.resolveHubUrl("http://api:5000/api"));
        assertEquals("http://localhost:5294/api/hubs/game", defaults.resolveHubUrl("http://localhost:5294/api/"));

        RealtimeSettings customPath = new RealtimeSettings(true, "s", "", "hubs/game", List.of(), 0);
        assertEquals("http://api:5000/hubs/game", customPath.resolveHubUrl("http://api:5000/api"));

        RealtimeSettings explicit = new RealtimeSettings(true, "s", "http://other/api/hubs/game", "", List.of(), 0);
        assertEquals("http://other/api/hubs/game", explicit.resolveHubUrl("http://api:5000/api"));
    }

    @Test
    void presencePayloadsUseTheApiFieldNames() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        var joined = RealtimeService.RosterPayloads.player(uuid, "Steve", "world", 7);
        var noUser = RealtimeService.RosterPayloads.player(uuid, "Steve", "world", null);
        var left = RealtimeService.RosterPayloads.left(uuid, "world_nether");

        assertEquals(List.of("uuid", "name", "world", "userId"), List.copyOf(joined.keySet()));
        assertEquals("00000000-0000-0000-0000-00000000000a", joined.get("uuid"));
        assertEquals(7, joined.get("userId"));
        assertFalse(noUser.containsKey("userId"));
        assertEquals(List.of("uuid", "world"), List.copyOf(left.keySet()));
    }
}
