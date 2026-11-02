package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#128: the anvil prompt's labels and text handling - Cancel on the left, a
 * green "Search for ..." (or "Clear search" when blank) on the right, the field starting with
 * the current value, and the typed text without colour codes or surrounding spaces.
 */
class MenuTextPromptTest {

    @Test
    void searchResultItemNamesWhatItWillSearchFor() {
        MenuTextPrompt.Button result = MenuTextPrompt.search("items", null).resultButton("Samurai");

        assertEquals("Search for \"Samurai\"", result.name());
        assertEquals(List.of("Click to search"), result.lore());
        assertEquals(MenuTextPrompt.Tone.CONFIRM, result.tone());
    }

    @Test
    void aBlankFieldClearsTheSearch() {
        MenuTextPrompt prompt = MenuTextPrompt.search("items", "Samurai");

        for (String blank : new String[] {null, "", "   "}) {
            MenuTextPrompt.Button result = prompt.resultButton(blank);
            assertEquals("Clear search", result.name());
            assertEquals(MenuTextPrompt.Tone.CLEAR, result.tone());
        }
    }

    @Test
    void theLeftItemIsCancelAndStartsTheFieldWithTheCurrentSearch() {
        MenuTextPrompt.Button cancel = MenuTextPrompt.search("items", "Samurai").cancelButton();

        assertEquals("Samurai", cancel.name(), "the client fills the rename field with the input item's name");
        assertEquals(List.of("Cancel", "Back to the menu (or press Escape)"), cancel.lore());
        assertEquals(MenuTextPrompt.Tone.CANCEL, cancel.tone());
    }

    @Test
    void withoutACurrentSearchTheFieldStartsEmpty() {
        assertEquals("", MenuTextPrompt.search("items", null).cancelButton().name());
        assertEquals("", MenuTextPrompt.search("items", "  ").initialText());
    }

    @Test
    void aLongCurrentValueIsCutToTheRenameFieldLimit() {
        String longText = "x".repeat(80);

        assertEquals(MenuTextPrompt.MAX_TEXT_LENGTH, MenuTextPrompt.search("items", longText).initialText().length());
    }

    @Test
    void filterPromptNamesTheFacet() {
        MenuTextPrompt prompt = MenuTextPrompt.filter("items", "category", "Weapons");

        assertEquals("Filter: category", prompt.title());
        assertEquals("Weapons", prompt.initialText());
        assertEquals("Filter category: \"Swords\"", prompt.resultButton("Swords").name());
        assertEquals("Clear filter category", prompt.resultButton("").name());
    }

    @Test
    void normalizeStripsColourCodesAndSpaces() {
        assertEquals("Samurai", MenuTextPrompt.normalize("  §aSam§lurai "));
        assertEquals("", MenuTextPrompt.normalize(null));
        assertEquals("a & b", MenuTextPrompt.normalize("a & b"));
    }

    @Test
    void theChatLineSaysWhichItemDoesWhat() {
        String line = MenuTextPrompt.search("items", null).chatLine();

        assertTrue(line.contains("paper on the right"));
        assertTrue(line.contains("Escape"));
    }
}
