package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuEnumParsingTest {

    @Test
    void parsesPascalCaseNamesFromTheWebApiConvention() {
        assertEquals(MenuSectionKind.CONTENT_GRID,
                MenuEnumParsing.parse(MenuSectionKind.class, "ContentGrid", null, "kind", "test"));
        assertEquals(VariableRefreshPolicyStandin.ON_DIRTY,
                MenuEnumParsing.parse(VariableRefreshPolicyStandin.class, "OnDirty", null, "refreshPolicy", "test"));
    }

    /** PandiO/knk-workspace#49: knk-web-api's MenuDisplayMode "Blink" is the plugin's BLINK. */
    @Test
    void webApiDisplayModeNamesMapOntoThePluginConstants() {
        assertEquals(MenuDisplayMode.BLINK,
                MenuEnumParsing.parse(MenuDisplayMode.class, "Blink", MenuDisplayMode.NORMAL, "displayMode", "test"));
        for (String name : new String[] {"Normal", "Disabled", "Highlight", "Hidden"}) {
            assertEquals(name.toUpperCase(java.util.Locale.ROOT),
                    MenuEnumParsing.parse(MenuDisplayMode.class, name, MenuDisplayMode.NORMAL, "displayMode", "test").name());
        }
    }

    @Test
    void nullRawReturnsTheProvidedDefaultWithoutError() {
        assertEquals(MenuOverflowMode.HIDE,
                MenuEnumParsing.parse(MenuOverflowMode.class, null, MenuOverflowMode.HIDE, "overflow", "test"));
    }

    @Test
    void unrecognizedNonNullValueThrows() {
        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () ->
                MenuEnumParsing.parse(MenuSectionKind.class, "NotARealKind", MenuSectionKind.CONTENT_GRID, "kind", "section 'x'"));

        assertTrue(ex.getMessage().contains("NotARealKind"));
        assertTrue(ex.getMessage().contains("section 'x'"));
    }

    /** A tiny local stand-in so this test doesn't depend on a real refresh-policy enum existing in knk-core.menu. */
    private enum VariableRefreshPolicyStandin {
        STATIC, ON_DIRTY, TTL
    }
}
