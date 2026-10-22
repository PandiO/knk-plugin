package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Performance metrics (KNG-232): the metrics block of config.yml. */
class ConfigLoaderMetricsTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderMetricsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigHasTheEndpointOffOnLoopback() throws Exception {
        KnkConfig.MetricsConfig metrics = ConfigLoader.load(bundledConfig()).metrics();

        assertEquals(KnkConfig.MetricsConfig.defaults(), metrics);
        assertFalse(metrics.enabled());
        assertEquals("127.0.0.1", metrics.bindAddress());
        assertEquals(9225, metrics.port());
        assertEquals(5, metrics.sampleSeconds());
    }

    @Test
    void aMissingSectionMeansOff() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("metrics", null);

        assertEquals(KnkConfig.MetricsConfig.defaults(), ConfigLoader.load(config).metrics());
    }

    @Test
    void valuesAreRead() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("metrics.enabled", true);
        config.set("metrics.bind-address", "172.17.0.1");
        config.set("metrics.port", 9300);
        config.set("metrics.token", "  s3cret ");
        config.set("metrics.sample-seconds", 10);

        KnkConfig.MetricsConfig metrics = ConfigLoader.load(config).metrics();

        assertTrue(metrics.enabled());
        assertEquals("172.17.0.1", metrics.bindAddress());
        assertEquals(9300, metrics.port());
        assertEquals("s3cret", metrics.token());
        assertEquals(10, metrics.sampleSeconds());
    }

    @Test
    void anOpenAddressWithoutATokenIsAConfigErrorOnlyWhenEnabled() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("metrics.bind-address", "0.0.0.0");
        ConfigLoader.load(config); // disabled: fine

        config.set("metrics.enabled", true);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));

        config.set("metrics.token", "x");
        ConfigLoader.load(config);
    }

    @Test
    void badPortAndSampleIntervalAreConfigErrors() throws Exception {
        YamlConfiguration port = bundledConfig();
        port.set("metrics.port", 70000);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(port));

        YamlConfiguration sample = bundledConfig();
        sample.set("metrics.sample-seconds", 0);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(sample));
    }
}
