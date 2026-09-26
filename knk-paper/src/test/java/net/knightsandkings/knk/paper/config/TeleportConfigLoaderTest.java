package net.knightsandkings.knk.paper.config;

import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The teleport: block of config.yml, including teleport.request (docs/specs/teleport/DESIGN.md §3.11). */
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
}
