package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** WP10: /account link tells the player where to enter the code. */
class LinkCodeMessageTest {

    @Test
    void withAPublicUrlTheRegisterLinkAndTheAccountPageAreMentioned() {
        List<String> lines = LinkCodeMessage.format(LinkCodeMessage.DEFAULT_TEMPLATE, "ABC-123", 20,
            "https://app.knightsandkings.net");

        assertEquals(List.of(
            "&aYour link code is &6ABC-123&a. It expires in 20 minutes.",
            "&aNew to the website? Register at &ehttps://app.knightsandkings.net/auth/register",
            "&aEnter the code when you register on the website, or on your Account page if you already have a web login."
        ), lines);
    }

    @Test
    void withoutAPublicUrlTheLinkLineIsLeftOut() {
        for (String noUrl : new String[] {"", "  ", null}) {
            List<String> lines = LinkCodeMessage.format(LinkCodeMessage.DEFAULT_TEMPLATE, "ABC-123", 20, noUrl);

            assertEquals(List.of(
                "&aYour link code is &6ABC-123&a. It expires in 20 minutes.",
                "&aEnter the code when you register on the website, or on your Account page if you already have a web login."
            ), lines);
            lines.forEach(line -> assertFalse(line.contains("{url}") || line.contains("/auth/register"), line));
        }
    }

    @Test
    void anOldSingleLineTemplateStillWorks() {
        assertEquals(List.of("&aYour link code is: &6ABC-123&a. Expires in 20 minutes."),
            LinkCodeMessage.format("&aYour link code is: &6{code}&a. Expires in {minutes} minutes.", "ABC-123", 20, ""));
    }

    @Test
    void aLiteralBackslashNAlsoSplitsLines() {
        assertEquals(List.of("one ABC", "two https://x/auth/register"),
            LinkCodeMessage.format("one {code}\\ntwo {url}/auth/register", "ABC", 5, "https://x"));
    }

    @Test
    void theBundledConfigShipsTheDefaultTemplates() throws Exception {
        try (InputStream in = LinkCodeMessageTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

            assertEquals(LinkCodeMessage.DEFAULT_TEMPLATE, config.getString("messages.link-code-generated"));
            assertEquals(LinkCodeMessage.DEFAULT_ENTERED_IN_GAME_TEMPLATE,
                config.getString("messages.link-code-entered-in-game"));
        }
    }
}
