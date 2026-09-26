package net.knightsandkings.knk.paper.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.chat.PrivateMessageFormat.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** KNG-18 Phase 1: private message lines (DESIGN.md §3.3.7). */
class PrivateMessageFormatTest {

    private static final Party ALICE = Party.player("Alice", NamedTextColor.DARK_PURPLE);
    private static final Party BOB = Party.player("Bob", null);
    private static final LocalTime AT = LocalTime.of(21, 4, 59);

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String hover(Component c) {
        @SuppressWarnings("unchecked")
        HoverEvent<Component> hover = (HoverEvent<Component>) c.hoverEvent();
        return plain(hover.value());
    }

    @Test
    void sender_line() {
        Component line = PrivateMessageFormat.toSender(BOB, "hi");

        assertEquals("[me -> Bob] hi", plain(line));
        assertEquals(ClickEvent.suggestCommand("/msg Bob "), line.clickEvent());
        assertEquals("Click to message Bob", hover(line));
    }

    @Test
    void recipient_line() {
        Component line = PrivateMessageFormat.toRecipient(ALICE, "hi", AT);

        assertEquals("[Alice -> me] hi", plain(line));
        assertEquals(ClickEvent.suggestCommand("/msg Alice "), line.clickEvent());
        assertEquals("Click to reply to Alice\nSent 21:04", hover(line));
    }

    @Test
    void recipient_line_nameInRankColour_defaultGray() {
        TextComponent alice = (TextComponent) PrivateMessageFormat.toRecipient(ALICE, "hi", AT).children().get(1);
        TextComponent bob = (TextComponent) PrivateMessageFormat.toRecipient(BOB, "hi", AT).children().get(1);

        assertEquals("Alice", alice.content());
        assertEquals(NamedTextColor.DARK_PURPLE, alice.color());
        assertEquals(NamedTextColor.GRAY, bob.color());
    }

    @Test
    void console_linesSuggestReply() {
        Component received = PrivateMessageFormat.toRecipient(Party.ofConsole(), "hi", AT);
        Component sent = PrivateMessageFormat.toSender(Party.ofConsole(), "ok");

        assertEquals("[CONSOLE -> me] hi", plain(received));
        assertEquals(ClickEvent.suggestCommand("/r "), received.clickEvent());
        assertEquals("[me -> CONSOLE] ok", plain(sent));
        assertEquals(ClickEvent.suggestCommand("/r "), sent.clickEvent());
    }

    @Test
    void spy_line_hasNoClick() {
        Component line = PrivateMessageFormat.toSpy(ALICE, BOB, "hi", AT, false, false);

        assertEquals("[Spy] Alice -> Bob: hi", plain(line));
        assertNull(line.clickEvent());
        assertEquals("Private message · 21:04", hover(line));
    }

    @Test
    void spy_line_viaReply_andIgnoredVariant() {
        Component line = PrivateMessageFormat.toSpy(ALICE, BOB, "hi", AT, true, true);

        assertEquals("[Spy][ignored] Alice -> Bob: hi", plain(line));
        assertEquals("Private message · 21:04 (via /r)", hover(line));
    }

    @Test
    void userText_isNeverParsed() {
        String text = "&c<b>red</b> §ahi <click:run_command:'/op me'>x</click>";

        Component line = PrivateMessageFormat.toRecipient(ALICE, text, AT);

        assertEquals("[Alice -> me] " + text, plain(line));
        TextComponent message = (TextComponent) line.children().get(line.children().size() - 1);
        assertEquals(text, message.content());
        assertNull(message.clickEvent());
    }

    @Test
    void usage_carriesTheModerationNote() {
        assertEquals("Usage: /msg <player> <message>\nPrivate messages may be read by moderators.",
                plain(PrivateMessageFormat.usage("/msg <player> <message>")));
    }
}
