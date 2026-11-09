package net.knightsandkings.knk.paper.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#184: {@code realtime.erasure.*}. */
class ErasureSettingsTest {

    @Test
    void theBundledConfig_GivesTheDefaults() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(ErasureSettings.defaults(), ErasureSettings.fromConfig(config));
        }
    }

    @Test
    void missingSection_Defaults_AndValuesAreBounded() {
        assertEquals(ErasureSettings.defaults(), ErasureSettings.fromConfig(new YamlConfiguration()));
        assertEquals(ErasureSettings.defaults(), ErasureSettings.fromConfig(null));

        YamlConfiguration config = new YamlConfiguration();
        config.set("realtime.erasure.kick-message", " ");
        config.set("realtime.erasure.settle-seconds", 90);
        config.set("realtime.erasure.feed-file", "privacy/feed.json");
        ErasureSettings settings = ErasureSettings.fromConfig(config);

        assertEquals(ErasureSettings.DEFAULT_KICK_MESSAGE, settings.kickMessage());
        assertEquals(Duration.ofSeconds(30), settings.settle());
        assertEquals("privacy/feed.json", settings.feedFile());
        assertEquals(ErasureSettings.DEFAULT_MINECRAFT_LIST, settings.minecraftList());

        config.set("realtime.erasure.settle-seconds", -1);
        assertEquals(Duration.ofSeconds(2), ErasureSettings.fromConfig(config).settle());
        config.set("realtime.erasure.settle-seconds", 0);
        assertEquals(Duration.ZERO, ErasureSettings.fromConfig(config).settle());
    }
}
