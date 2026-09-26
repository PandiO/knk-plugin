package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.format.NamedTextColor;

/** KNG-7: PermissionGroup color names from the API → Adventure colors. */
class NamedColorsTest {

    @Test
    void parsesEveryMinecraftColorName() {
        String[] names = { "BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY",
            "DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE" };
        for (String name : names) {
            assertEquals(NamedTextColor.NAMES.value(name.toLowerCase()), NamedColors.parse(name, null), name);
        }
        assertEquals(16, names.length);
    }

    @Test
    void isLenientAboutCaseAndSpaces() {
        assertEquals(NamedTextColor.DARK_RED, NamedColors.parse(" dark red ", NamedTextColor.WHITE));
    }

    @Test
    void unknownOrBlank_returnsFallback() {
        assertEquals(NamedTextColor.WHITE, NamedColors.parse(null, NamedTextColor.WHITE));
        assertEquals(NamedTextColor.WHITE, NamedColors.parse("", NamedTextColor.WHITE));
        assertEquals(NamedTextColor.WHITE, NamedColors.parse("PINK", NamedTextColor.WHITE));
    }
}
