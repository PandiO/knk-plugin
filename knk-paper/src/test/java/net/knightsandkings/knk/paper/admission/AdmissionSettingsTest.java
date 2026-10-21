package net.knightsandkings.knk.paper.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.admission.AdmissionPolicy;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Mode;

class AdmissionSettingsTest {
    @Test
    void withoutASectionTheGateIsOff() {
        AdmissionSettings settings = AdmissionSettings.fromConfig(null, "https://knightsandkings.net/");

        assertEquals(Mode.WHITELIST, settings.mode());
        assertEquals(3000L, settings.apiTimeoutMs());
        assertEquals("https://knightsandkings.net/link", settings.linkUrl());
        assertEquals(AdmissionSettings.DEFAULT_KICK, settings.message(AdmissionPolicy.KICK));
    }

    @Test
    void readsTheSection() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            admission:
              mode: invite
              api-timeout-ms: 1500
              link-url: "https://example.test/claim"
              kick: "&cGo to {url}: {code}"
              unavailable: "down"
              deactivated: "gone"
              account-linked: "linked {maskedEmail}"
            """);

        AdmissionSettings settings = AdmissionSettings.fromConfig(yaml.getConfigurationSection("admission"), "https://x");

        assertEquals(Mode.INVITE, settings.mode());
        assertEquals(1500L, settings.apiTimeoutMs());
        assertEquals("https://example.test/claim", settings.linkUrl());
        assertEquals("&cGo to {url}: {code}", settings.message(AdmissionPolicy.KICK));
        assertEquals("down", settings.message(AdmissionPolicy.UNAVAILABLE));
        assertEquals("gone", settings.message(AdmissionPolicy.DEACTIVATED));
        assertEquals("linked {maskedEmail}", settings.accountLinkedMessage());
    }

    @Test
    void anUnknownModeFallsBackToWhitelistAndEmptyLinkUrlUsesThePublicUrl() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            admission:
              mode: closed
              api-timeout-ms: 0
              link-url: ""
            """);

        AdmissionSettings settings = AdmissionSettings.fromConfig(yaml.getConfigurationSection("admission"), "");

        assertEquals(Mode.WHITELIST, settings.mode());
        assertEquals(3000L, settings.apiTimeoutMs());
        assertEquals("", settings.linkUrl());
    }

    @Test
    void theShippedConfigKeepsTheGateOff() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        AdmissionSettings settings = AdmissionSettings.fromConfig(yaml.getConfigurationSection("admission"),
            yaml.getString("web.public-url"));

        assertEquals(Mode.WHITELIST, settings.mode());
        assertEquals(3000L, settings.apiTimeoutMs());
    }
}
