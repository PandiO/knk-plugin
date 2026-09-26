package net.knightsandkings.knk.paper.config;

import net.knightsandkings.knk.core.teleport.TeleportBackSettings;
import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The teleport: block of config.yml, including teleport.request and teleport.back (docs/specs/teleport/DESIGN.md §3.11). */
class TeleportConfigLoaderTest {

    @Test
    void requestBlockIsRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("teleport.warmup-seconds", 4);
        yaml.set("teleport.request.expire-seconds", 45);
        yaml.set("teleport.request.cooldown-seconds", 3);
        yaml.set("teleport.request.max-incoming", 2);
        yaml.set("teleport.request.price-coins", 0);

        TeleportSettings settings = ConfigLoader.loadTeleportSettings(yaml.getConfigurationSection("teleport"));

        assertEquals(4, settings.warmupSeconds());
        assertEquals(new TeleportRequestSettings(45, 3, 2, 0), settings.request());
    }

    @Test
    void missingRequestKeysUseTheDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("teleport.warmup-seconds", 5);
        yaml.set("teleport.request.expire-seconds", 20);

        TeleportSettings settings = ConfigLoader.loadTeleportSettings(yaml.getConfigurationSection("teleport"));

        assertEquals(new TeleportRequestSettings(20, 10, 5, 0), settings.request());
        assertEquals(TeleportRequestSettings.defaults(),
            ConfigLoader.loadTeleportSettings(null).request());
    }

    @Test
    void destinationsCacheSecondsIsRead_AndDefaultsToAMinute() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("teleport.destinations.cache-seconds", 15);

        assertEquals(15, ConfigLoader.loadTeleportSettings(yaml.getConfigurationSection("teleport")).destinationsCacheSeconds());
        assertEquals(60, ConfigLoader.loadTeleportSettings(null).destinationsCacheSeconds());
        YamlConfiguration empty = new YamlConfiguration();
        empty.set("teleport.warmup-seconds", 5);
        assertEquals(60, ConfigLoader.loadTeleportSettings(empty.getConfigurationSection("teleport")).destinationsCacheSeconds());
    }

    @Test
    void backBlockIsRead_AndDefaultsToFiveMinutes() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("teleport.back.enabled", false);
        yaml.set("teleport.back.expire-seconds", 120);

        assertEquals(new TeleportBackSettings(false, 120),
            ConfigLoader.loadTeleportSettings(yaml.getConfigurationSection("teleport")).back());
        assertEquals(new TeleportBackSettings(true, 300), ConfigLoader.loadTeleportSettings(null).back());
        YamlConfiguration partial = new YamlConfiguration();
        partial.set("teleport.back.expire-seconds", 60);
        assertEquals(new TeleportBackSettings(true, 60),
            ConfigLoader.loadTeleportSettings(partial.getConfigurationSection("teleport")).back());
    }

    @Test
    void shippedConfigEnablesBackForFiveMinutes() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            shipped.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }

        assertEquals(TeleportBackSettings.defaults(),
            ConfigLoader.loadTeleportSettings(shipped.getConfigurationSection("teleport")).back());
    }
}
