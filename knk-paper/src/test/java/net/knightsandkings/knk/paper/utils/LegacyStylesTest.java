package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** KNG-7: PermissionGroup "&amp;" formatting codes from the API → Adventure styles. */
class LegacyStylesTest {

    private static final Style FALLBACK = Style.style(NamedTextColor.WHITE);

    @Test
    void parsesEverySingleColorCode() {
        String codes = "0123456789abcdef";
        for (char c : codes.toCharArray()) {
            Style style = LegacyStyles.parse("&" + c, null);
            assertEquals(NamedTextColor.class, style.color().getClass(), "&" + c);
        }
        assertEquals(NamedTextColor.YELLOW, LegacyStyles.parse("&e", FALLBACK).color());
        assertEquals(NamedTextColor.DARK_RED, LegacyStyles.parse("&4", FALLBACK).color());
    }

    @Test
    void colorThenFormat_keepsBoth() {
        Style style = LegacyStyles.parse("&6&l", FALLBACK);
        assertEquals(NamedTextColor.GOLD, style.color());
        assertEquals(TextDecoration.State.TRUE, style.decoration(TextDecoration.BOLD));
    }

    @Test
    void formatThenColor_colorResetsFormat_asInMinecraft() {
        Style style = LegacyStyles.parse("&l&6", FALLBACK);
        assertEquals(NamedTextColor.GOLD, style.color());
        assertNotEquals(TextDecoration.State.TRUE, style.decoration(TextDecoration.BOLD));
    }

    @Test
    void hexColor() {
        assertEquals(TextColor.color(0xFFAA00), LegacyStyles.parse("&x&f&f&a&a&0&0", FALLBACK).color());
    }

    @Test
    void sectionSignAndSurroundingSpaces_areAccepted() {
        assertEquals(NamedTextColor.AQUA, LegacyStyles.parse(" §b ", FALLBACK).color());
    }

    @Test
    void blankOrNoCodes_returnsFallback() {
        assertEquals(FALLBACK, LegacyStyles.parse(null, FALLBACK));
        assertEquals(FALLBACK, LegacyStyles.parse("  ", FALLBACK));
        assertEquals(FALLBACK, LegacyStyles.parse("YELLOW", FALLBACK));
        assertNull(LegacyStyles.parse("", null));
    }

    @Test
    void nameColor_namedHexAndFallback() {
        assertEquals(NamedTextColor.AQUA, LegacyStyles.nameColor("&b&l", NamedTextColor.GRAY));
        assertEquals(NamedTextColor.GOLD, LegacyStyles.nameColor("&x&f&f&a&a&0&0", NamedTextColor.GRAY));
        assertEquals(NamedTextColor.GRAY, LegacyStyles.nameColor("&l", NamedTextColor.GRAY));
        assertEquals(NamedTextColor.GRAY, LegacyStyles.nameColor(null, NamedTextColor.GRAY));
    }
}
