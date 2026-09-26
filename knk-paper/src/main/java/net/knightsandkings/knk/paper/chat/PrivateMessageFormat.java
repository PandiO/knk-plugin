package net.knightsandkings.knk.paper.chat;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Private message lines (docs/specs/private-messages/DESIGN.md §3.3.7). Kept free of Bukkit types
 * so it is unit testable, like {@link ChatLineFormat}; MessagingService picks the name colours
 * (rank colour from TabListTeam) and the time.
 * <p>
 * The message text is always a plain {@link Component#text(String)} - never MiniMessage or
 * legacy-parsed - so a player can't inject colours, click events or fake lines (DESIGN.md §4 D7).
 * <ul>
 *   <li>Sender: {@code [me -> Bob] hi}, click fills {@code /msg Bob }.</li>
 *   <li>Recipient: {@code [Alice -> me] hi}, hover shows the time, click fills {@code /msg Alice }
 *   (stable even if the reply target moves on).</li>
 *   <li>Spy: {@code [Spy] Alice -> Bob: hi}, no click (avoids accidental replies).</li>
 * </ul>
 * The console can't be {@code /msg}'d, so a line naming the console fills {@code /r } instead.
 */
public final class PrivateMessageFormat {
    private PrivateMessageFormat() {}

    public static final String CONSOLE_NAME = "CONSOLE";
    public static final String MODERATION_NOTE = "Private messages may be read by moderators.";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final TextColor FRAME = ColorOptions.message;
    private static final TextColor ME = NamedTextColor.BLUE;
    private static final TextColor DEFAULT_NAME = NamedTextColor.GRAY;
    private static final TextColor CONSOLE_COLOR = NamedTextColor.GOLD;

    /** One end of a PM as shown in a line; {@code color} is the rank colour (null = default gray). */
    public record Party(String name, TextColor color, boolean console) {
        public static Party player(String name, TextColor color) {
            return new Party(name, color, false);
        }

        public static Party ofConsole() {
            return new Party(CONSOLE_NAME, CONSOLE_COLOR, true);
        }

        Component nameComponent() {
            return Component.text(name, color != null ? color : DEFAULT_NAME);
        }

        String replyCommand() {
            return console ? "/r " : "/msg " + name + " ";
        }
    }

    /** {@code [me -> Bob] hi} - the sender's echo. */
    public static Component toSender(Party recipient, String text) {
        String hover = recipient.console() ? "Click to reply" : "Click to message " + recipient.name();
        return Component.text()
                .append(Component.text("[", FRAME))
                .append(Component.text("me", ME))
                .append(Component.text(" -> ", FRAME))
                .append(recipient.nameComponent())
                .append(Component.text("] ", FRAME))
                .append(Component.text(text, FRAME))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.suggestCommand(recipient.replyCommand()))
                .build();
    }

    /** {@code [Alice -> me] hi} - what the recipient sees. */
    public static Component toRecipient(Party sender, String text, LocalTime sentAt) {
        String hover = sender.console() ? "Click to reply" : "Click to reply to " + sender.name();
        return Component.text()
                .append(Component.text("[", FRAME))
                .append(sender.nameComponent())
                .append(Component.text(" -> ", FRAME))
                .append(Component.text("me", ME))
                .append(Component.text("] ", FRAME))
                .append(Component.text(text, FRAME))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("Sent " + TIME.format(sentAt), NamedTextColor.DARK_GRAY))))
                .clickEvent(ClickEvent.suggestCommand(sender.replyCommand()))
                .build();
    }

    /**
     * {@code [Spy] Alice -> Bob: hi}; {@code [Spy][ignored] …} for a message the recipient's ignore
     * list swallowed (Phase 2).
     */
    public static Component toSpy(Party sender, Party recipient, String text, LocalTime sentAt, boolean viaReply, boolean ignored) {
        Component hover = Component.text("Private message · " + TIME.format(sentAt), NamedTextColor.GRAY);
        if (viaReply) {
            hover = hover.append(Component.text(" (via /r)", NamedTextColor.DARK_GRAY));
        }
        TextComponent.Builder line = Component.text()
                .append(Component.text(ignored ? "[Spy][ignored] " : "[Spy] ", NamedTextColor.DARK_GRAY))
                .append(sender.nameComponent())
                .append(Component.text(" -> ", NamedTextColor.DARK_GRAY))
                .append(recipient.nameComponent())
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(hover));
        return line.build();
    }

    /** Usage line plus the one-line monitoring notice (DESIGN.md §5 Q4). */
    public static Component usage(String usage) {
        return Component.text("Usage: " + usage, NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(Component.text(MODERATION_NOTE, NamedTextColor.GRAY));
    }
}
