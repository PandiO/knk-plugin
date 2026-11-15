package net.knightsandkings.knk.paper.realtime;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PandiO/knk-workspace#574: {@code realtime.moderation.*} and its defaults. */
class ModerationSettingsTest {

    @Test
    void missingSection_Defaults() {
        assertEquals(ModerationSettings.defaults(), ModerationSettings.fromConfig(new YamlConfiguration()));
        assertEquals(ModerationSettings.defaults(), ModerationSettings.fromConfig(null));
    }

    @Test
    void blankKeys_Defaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.moderation.kick-default-reason", " ");
        config.set("realtime.moderation.ban-message", "");
        assertEquals(ModerationSettings.defaults(), ModerationSettings.fromConfig(config));
    }

    @Test
    void readsKeys() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.moderation.kick-default-reason", "Bye");
        config.set("realtime.moderation.ban-message", "Gone: {reason}");
        ModerationSettings settings = ModerationSettings.fromConfig(config);
        assertEquals("Bye", settings.kickDefaultReason());
        assertEquals("Gone: {reason}", settings.banMessage());
        assertEquals(ModerationSettings.DEFAULT_BAN_SOURCE, settings.banSource());
    }

    @Test
    void shippedConfigMatchesTheDefaults() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            assertEquals(ModerationSettings.defaults(), ModerationSettings.fromConfig(YamlConfiguration.loadConfiguration(reader)));
        }
    }

    @Test
    void fillPlaceholders() {
        assertEquals("X-ray by Mod for Steve", ModerationSettings.fill("{reason} by {staff} for {player}", "X-ray", "Steve", "Mod"));
        assertEquals(" by  for ", ModerationSettings.fill("{reason} by {staff} for {player}", null, null, null));
    }
}
