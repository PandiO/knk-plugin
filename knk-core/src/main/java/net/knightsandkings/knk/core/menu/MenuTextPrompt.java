package net.knightsandkings.knk.core.menu;

import java.util.List;
import java.util.regex.Pattern;

/**
 * PandiO/knk-workspace#128: what the menu's anvil text prompt (search, or one filter facet) shows
 * and how it reads the typed text. Bukkit-free, so labels and text handling are tested without a
 * server; {@code knk-paper}'s {@code AnvilCaptureManager} turns the {@link Button}s into items.
 * <p>
 * The anvil has two items:
 * <ul>
 *   <li>the <b>input</b> (left) item is Cancel. Its name is the text the rename field starts
 *       with (the client fills the field with the input item's name), so it carries the current
 *       search or filter value, or nothing, and says "Cancel" in its lore instead of its name. A
 *       name "Cancel" would put "Cancel" in front of whatever the player types.</li>
 *   <li>the <b>result</b> (right) item confirms: {@code Search for "<text>"}, or
 *       {@code Clear search} when the field is blank.</li>
 * </ul>
 */
public record MenuTextPrompt(
        String title,
        String chatLine,
        String initialText,
        String confirmPrefix,
        String clearLabel,
        String confirmHint,
        String clearHint
) {

    /** Vanilla's rename field limit; a longer current value would be cut by the client anyway. */
    public static final int MAX_TEXT_LENGTH = 50;

    public static final String CANCEL_LABEL = "Cancel";
    public static final String CANCEL_HINT = "Back to the menu (or press Escape)";

    private static final Pattern LEGACY_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    /** The look of one anvil item. */
    public enum Tone {
        /** The input item: red "Cancel" lore. */
        CANCEL,
        /** The result item with text typed: green. */
        CONFIRM,
        /** The result item with a blank field: yellow "Clear …". */
        CLEAR
    }

    /**
     * One anvil item: its name and lore lines, plain text (no colour codes); {@code tone} picks
     * the colours.
     */
    public record Button(String name, List<String> lore, Tone tone) {
        public Button {
            lore = List.copyOf(lore);
        }
    }

    public MenuTextPrompt {
        initialText = clip(normalize(initialText));
    }

    /** The search prompt of section {@code sectionName}; {@code currentText} is its search now (may be null). */
    public static MenuTextPrompt search(String sectionName, String currentText) {
        return new MenuTextPrompt(
                "Search",
                "Type a search for '" + sectionName + "' and click the paper on the right."
                        + " The left item (or Escape) takes you back.",
                currentText,
                "Search for ",
                "Clear search",
                "Click to search",
                "Click to show everything");
    }

    /** The prompt for filter {@code facetKey} of section {@code sectionName}; {@code currentValue} may be null. */
    public static MenuTextPrompt filter(String sectionName, String facetKey, String currentValue) {
        String facet = facetKey == null || facetKey.isBlank() ? "filter" : facetKey;
        return new MenuTextPrompt(
                "Filter: " + facet,
                "Type a value for filter '" + facet + "' on '" + sectionName + "' and click the paper on the right."
                        + " The left item (or Escape) takes you back.",
                currentValue,
                "Filter " + facet + ": ",
                "Clear filter " + facet,
                "Click to filter",
                "Click to remove this filter");
    }

    /** The input (left) item: named after the starting text, Cancel in its lore. */
    public Button cancelButton() {
        return new Button(initialText, List.of(CANCEL_LABEL, CANCEL_HINT), Tone.CANCEL);
    }

    /** The result (right) item for what the rename field holds now ({@code renameText} may be null). */
    public Button resultButton(String renameText) {
        String text = normalize(renameText);
        if (text.isEmpty()) {
            return new Button(clearLabel, List.of(clearHint), Tone.CLEAR);
        }
        return new Button(confirmPrefix + "\"" + text + "\"", List.of(confirmHint), Tone.CONFIRM);
    }

    /**
     * The text to hand to search/filter: colour codes removed and trimmed; null becomes "". An
     * empty result means "clear" to {@code MenuContentQuery}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return LEGACY_FORMATTING.matcher(raw).replaceAll("").trim();
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }
}
