package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** New-player onboarding (KNG-128/129): the onboarding: block. */
class ConfigLoaderOnboardingTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderOnboardingTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigMatchesTheDefaults() throws Exception {
        assertEquals(KnkConfig.OnboardingConfig.defaults(), ConfigLoader.load(bundledConfig()).onboarding());
    }

    @Test
    void anOlderConfigWithoutTheSectionGetsTheDefaults() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("onboarding", null);

        KnkConfig.OnboardingConfig onboarding = ConfigLoader.load(config).onboarding();

        assertEquals(KnkConfig.OnboardingConfig.defaults(), onboarding);
        assertEquals(Duration.ofMinutes(10), onboarding.discoveryQuiet());
    }

    @Test
    void valuesCanBeOverridden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("onboarding.first-join-welcome", List.of("&aHi {player}"));
        yaml.set("onboarding.discovery-quiet-minutes", 0);
        yaml.set("onboarding.unlinked-reminder.enabled", false);
        yaml.set("onboarding.unlinked-reminder.message", "&7Link? ");

        KnkConfig.OnboardingConfig onboarding = ConfigLoader.loadOnboarding(yaml.getConfigurationSection("onboarding"));

        assertEquals(List.of("&aHi {player}"), onboarding.firstJoinWelcome());
        assertEquals(0, onboarding.discoveryQuietMinutes());
        assertFalse(onboarding.unlinkedReminderEnabled());
        assertEquals("&7Link? ", onboarding.unlinkedReminder());
        assertEquals(KnkConfig.OnboardingConfig.defaults().linkButton(), onboarding.linkButton());
    }

    @Test
    void quietMinutesOutOfRangeAreAConfigError() throws Exception {
        YamlConfiguration config = bundledConfig();
        config.set("onboarding.discovery-quiet-minutes", 61);

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }
}
