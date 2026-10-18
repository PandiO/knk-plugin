package net.knightsandkings.knk.paper.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.config.KnkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class OnboardingMessagesTest {
    private static final KnkConfig.OnboardingConfig DEFAULTS = KnkConfig.OnboardingConfig.defaults();

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void theWelcomeNamesThePlayer() {
        List<String> lines = OnboardingMessages.firstJoinWelcome(DEFAULTS.firstJoinWelcome(), "Steve").stream()
            .map(OnboardingMessagesTest::plain).toList();

        assertEquals("Welcome to Knights & Kings, Steve!", lines.get(0));
        assertEquals(DEFAULTS.firstJoinWelcome().size(), lines.size());
    }

    @Test
    void theLinkButtonRunsAccountLink() {
        Component prompt = OnboardingMessages.linkPrompt(DEFAULTS.unlinkedReminder(), DEFAULTS.linkButton(), DEFAULTS.linkButtonHover());

        assertEquals("You haven't linked a web account yet. [Link it now]", plain(prompt));
        Component button = prompt.children().get(prompt.children().size() - 1);
        ClickEvent click = button.clickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/account link", click.value());
        assertNotNull(button.hoverEvent());
    }
}
