package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Diagnostic telemetry (KNG-34 link 6, IMPLEMENTATION_PLAN.md §5.1): the telemetry block of config.yml. */
class ConfigLoaderTelemetryTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderTelemetryTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigLoadsWithThePlanDefaults() throws Exception {
        KnkConfig.TelemetryConfig telemetry = ConfigLoader.load(bundledConfig()).telemetry();

        assertEquals(KnkConfig.TelemetryConfig.defaults(), telemetry);
        assertEquals(new KnkConfig.TelemetryConfig(true, 5000, 10, 60, 5), telemetry);
    }

    @Test
    void aMissingSection_meansOnWithDefaults_andTheSwitchIsRead() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("telemetry", null);
        assertEquals(KnkConfig.TelemetryConfig.defaults(), ConfigLoader.load(config).telemetry());

        config.set("telemetry.enabled", false);
        config.set("telemetry.max-buffer-events", 100);
        KnkConfig.TelemetryConfig off = ConfigLoader.load(config).telemetry();
        assertFalse(off.enabled());
        assertEquals(100, off.maxBufferEvents());
        assertEquals(10, off.flushIntervalSeconds());
    }

    @Test
    void badValues_areConfigErrors() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("telemetry.max-buffer-events", 5);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));

        YamlConfiguration poll = bundledConfig();
        poll.set("telemetry.config-poll-seconds", 1);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(poll));
    }

    @Test
    void olderConstructors_defaultTheTelemetrySection() {
        KnkConfig config = new KnkConfig(null, null, null, null, KnkConfig.PrivateMessagesConfig.defaults(), null,
            KnkConfig.DiscoveryConfig.defaults(), KnkConfig.StatisticsConfig.defaults());
        assertEquals(KnkConfig.TelemetryConfig.defaults(), config.telemetry());
    }
}
