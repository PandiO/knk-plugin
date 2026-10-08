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

/** Closed-alpha hardening (WP10): region-http, web.public-url and the TLS default. */
class ConfigLoaderHardeningTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderHardeningTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            // The shipped config uses auth type apikey with an empty key, which the loader refuses on purpose.
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigIsSafeByDefault() throws Exception {
        KnkConfig config = ConfigLoader.load(bundledConfig());

        assertFalse(config.api().allowUntrustedSsl());
        assertEquals("127.0.0.1", config.regionHttp().bindAddress());
        assertEquals(8081, config.regionHttp().port());
        assertEquals("", config.web().publicUrl());
        assertFalse(config.web().hasPublicUrl());
    }

    @Test
    void anOlderConfigWithoutTheNewSectionsGetsTheDefaults() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("region-http", null);
        config.set("web", null);

        KnkConfig loaded = ConfigLoader.load(config);

        assertEquals(KnkConfig.RegionHttpConfig.defaults(), loaded.regionHttp());
        assertEquals(KnkConfig.WebConfig.defaults(), loaded.web());
    }

    @Test
    void anOlderRegionHttpSectionWithOnlyAPortKeepsThePortAndBindsToLoopback() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("region-http.port", 9090);

        KnkConfig.RegionHttpConfig regionHttp = ConfigLoader.loadRegionHttp(yaml.getConfigurationSection("region-http"));

        assertEquals("127.0.0.1", regionHttp.bindAddress());
        assertEquals(9090, regionHttp.port());
    }

    @Test
    void valuesCanBeOverridden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("region-http.bind-address", " 172.17.0.1 ");
        yaml.set("region-http.port", 8082);
        yaml.set("web.public-url", "https://app.knightsandkings.net/");

        assertEquals(new KnkConfig.RegionHttpConfig("172.17.0.1", 8082),
            ConfigLoader.loadRegionHttp(yaml.getConfigurationSection("region-http")));
        KnkConfig.WebConfig web = ConfigLoader.loadWeb(yaml.getConfigurationSection("web"));
        assertEquals("https://app.knightsandkings.net", web.publicUrl());
        assertTrue(web.hasPublicUrl());
    }

    @Test
    void aBlankBindAddressFallsBackToLoopback() {
        assertEquals("127.0.0.1", new KnkConfig.RegionHttpConfig("  ", 8081).bindAddress());
        assertEquals("127.0.0.1", new KnkConfig.RegionHttpConfig(null, 8081).bindAddress());
    }

    @Test
    void anOutOfRangePortIsAConfigError() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("region-http.port", 70000);

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }
}
