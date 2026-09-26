package net.knightsandkings.knk.paper.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.chat.ChatLineFormat.Rank;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * KNG-8 (title in chat) + KNG-7 (premium tier colors): v1's {@code <tag>-{<Title>}- Name: msg}.
 */
class ChatLineFormatTest {

    private static final Component MSG = Component.text("Hello");

    private static UserSummary summary(String title, Integer tierId, String tier, String primary, String secondary) {
        return new UserSummary(1, "Pandi", UUID.randomUUID(), null, 0, 0, 0, false, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, title == null ? null : 3, title, 0,
            tierId, tier, null, false, null, null, primary, secondary, primary);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** The line's text pieces in order (flattened), for checking per-piece color/bold. */
    private static List<TextComponent> pieces(Component c) {
        List<TextComponent> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, List<TextComponent> out) {
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t);
        }
        c.children().forEach(child -> collect(child, out));
    }

    private static TextComponent piece(Component line, String content) {
        return pieces(line).stream().filter(t -> t.content().equals(content)).findFirst()
            .orElseThrow(() -> new AssertionError("no piece '" + content + "' in " + plain(line)));
    }

    @Test
    void owner_withTitle_boldTitleAfterTag() {
        Component line = ChatLineFormat.render(Rank.OWNER, "Pandi", summary("Knight", null, null, null, null), MSG);

        assertEquals("[OWNER]-{Knight}- Pandi: Hello", plain(line));
        assertEquals(ColorOptions.ownerformat, piece(line, "-{").color());
        assertEquals(ColorOptions.ownersubjects, piece(line, "Knight").color());
        assertEquals(TextDecoration.State.TRUE, piece(line, "Knight").decoration(TextDecoration.BOLD));
        assertEquals(ColorOptions.ownersubjects, piece(line, " Pandi: ").color());
    }

    @Test
    void owner_ignoresPremiumTierAndItsColors() {
        Component line = ChatLineFormat.render(Rank.OWNER, "Pandi", summary("Knight", 12, "Noble", "&e", "&6"), MSG);

        assertEquals("[OWNER]-{Knight}- Pandi: Hello", plain(line));
        assertEquals(ColorOptions.ownersubjects, piece(line, "Knight").color());
    }

    @Test
    void owner_withoutSummary_keepsPreviousFormat() {
        assertEquals("[OWNER] Pandi: Hello", plain(ChatLineFormat.render(Rank.OWNER, "Pandi", null, MSG)));
    }

    @Test
    void staff_withTitle_usesStaffColors() {
        Component line = ChatLineFormat.render(Rank.STAFF, "Pandi", summary("Knight", null, null, null, null), MSG);

        assertEquals("[STAFF]-{Knight}- Pandi: Hello", plain(line));
        assertEquals(ColorOptions.staffformat, piece(line, "[").color());
        assertEquals(ColorOptions.staffsubjects, piece(line, "STAFF").color());
        assertEquals(TextDecoration.State.TRUE, piece(line, "Knight").decoration(TextDecoration.BOLD));
    }

    @Test
    void premiumTier_tierNameInsideBraces_tierColors_notBold() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", summary("Knight", 12, "Noble", "&e", "&6"), MSG);

        assertEquals("-{Noble Knight}- Pandi: Hello", plain(line));
        assertEquals(NamedTextColor.GOLD, piece(line, "-{").color());
        assertEquals(NamedTextColor.GOLD, piece(line, "}-").color());
        assertEquals(NamedTextColor.YELLOW, piece(line, "Noble Knight").color());
        assertNotEquals(TextDecoration.State.TRUE, piece(line, "Noble Knight").decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.YELLOW, piece(line, " Pandi: ").color());
    }

    @Test
    void premiumTier_withoutTitle_showsTierAlone() {
        assertEquals("-{Dragon Blood}- Pandi: Hello",
            plain(ChatLineFormat.render(Rank.MEMBER, "Pandi", summary(null, 13, "Dragon Blood", "&c", "&4"), MSG)));
    }

    @Test
    void default_withDbColors_usesThem() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", summary("Knight", null, null, "&a", "&2"), MSG);

        assertEquals("-{Knight}- Pandi: Hello", plain(line));
        assertEquals(NamedTextColor.DARK_GREEN, piece(line, "-{").color());
        assertEquals(NamedTextColor.GREEN, piece(line, "Knight").color());
    }

    @Test
    void default_withoutColors_fallsBackToColorOptions() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", summary("Knight", null, null, null, "NOT_CODES"), MSG);

        assertEquals(ColorOptions.defaultformat, piece(line, "-{").color());
        assertEquals(ColorOptions.defaultsubjects, piece(line, "Knight").color());
    }

    @Test
    void default_withoutSummary_isJustNameAndMessage() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", null, MSG);

        assertEquals("Pandi: Hello", plain(line));
        assertEquals(ColorOptions.defaultsubjects, piece(line, "Pandi: ").color());
    }

    @Test
    void message_takesNameColorButNotItsFormats() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", summary("Knight", 12, "Royal", "&b&l", "&9"), MSG);

        assertEquals(TextDecoration.State.TRUE, piece(line, " Pandi: ").decoration(TextDecoration.BOLD));
        TextComponent message = piece(line, "Hello");
        assertEquals(NamedTextColor.AQUA, message.color());
        assertNotEquals(TextDecoration.State.TRUE, message.decoration(TextDecoration.BOLD));
    }

    @Test
    void message_ownColorCodesWin() {
        Component colored = Component.text("Hi", NamedTextColor.RED);
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi", summary(null, null, null, "&a", "&2"), colored);

        assertEquals(NamedTextColor.RED, piece(line, "Hi").color());
    }

    @Test
    void premiumTier_boldAndHexStyles() {
        Component line = ChatLineFormat.render(Rank.MEMBER, "Pandi",
            summary("Knight", 20, "Emperor", "&x&f&f&a&a&0&0&l", "&5"), MSG);

        assertEquals("-{Emperor Knight}- Pandi: Hello", plain(line));
        assertEquals(TextColor.color(0xFFAA00), piece(line, "Emperor Knight").color());
        assertEquals(TextDecoration.State.TRUE, piece(line, "Emperor Knight").decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.DARK_PURPLE, piece(line, "-{").color());
    }

    @Test
    void blankTitle_isTreatedAsMissing() {
        assertEquals("Pandi: Hello", plain(ChatLineFormat.render(Rank.MEMBER, "Pandi", summary(" ", null, null, null, null), MSG)));
    }
}
