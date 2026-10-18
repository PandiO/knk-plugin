package net.knightsandkings.knk.paper.onboarding;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The onboarding chat lines (KNG-128/129), from the {@code onboarding:} templates in config.yml
 * ({@code &} colour codes). The link prompt ends in a button that runs {@code /account link}, which
 * answers with the code (click to copy) and the register URL. Free of Bukkit types so it is unit testable.
 */
public final class OnboardingMessages {
    public static final String LINK_COMMAND = "/account link";
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private OnboardingMessages() {}

    /** The first-join welcome, one component per configured line; {player} is the player's name. */
    public static List<Component> firstJoinWelcome(List<String> lines, String playerName) {
        String name = playerName == null ? "" : playerName;
        return lines.stream().map(line -> (Component) AMPERSAND.deserialize(line.replace("{player}", name))).toList();
    }

    /** {@code text} followed by the button; clicking the button runs {@code /account link}. */
    public static Component linkPrompt(String text, String button, String hover) {
        Component clickable = AMPERSAND.deserialize(button)
            .clickEvent(ClickEvent.runCommand(LINK_COMMAND))
            .hoverEvent(HoverEvent.showText(AMPERSAND.deserialize(hover)));
        return AMPERSAND.deserialize(text).append(clickable);
    }
}
