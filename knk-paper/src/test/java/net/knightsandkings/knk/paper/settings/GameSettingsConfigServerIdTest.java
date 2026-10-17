package net.knightsandkings.knk.paper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** KNG-118 (A9): {@code game-settings.server-id} names this server in the runtime-worlds report. */
class GameSettingsConfigServerIdTest {

    @Test
    void serverIdDefaultsToDefault() {
        assertEquals("default", GameSettingsConfig.from(new YamlConfiguration()).serverId());
        assertEquals("default", GameSettingsConfig.defaults().serverId());
    }

    @Test
    void serverIdIsReadAndTrimmed_AndBlankFallsBackToDefault() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("game-settings.server-id", "  hub-server ");
        assertEquals("hub-server", GameSettingsConfig.from(yaml).serverId());

        yaml.set("game-settings.server-id", " ");
        assertEquals("default", GameSettingsConfig.from(yaml).serverId());
    }
}
