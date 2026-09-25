package net.knightsandkings.knk.core.menu;

import java.util.regex.Pattern;

/**
 * InventoryMenu Phase 9 (E9): the engine root {@code $menu$} - read-only facts
 * about the menu being rendered and its place in the player's navigation stack,
 * for Back/Exit buttons: {@code &c$menu.getBackLabel$} / {@code &7$menu.getBackHint$}
 * (v2's intended {@code %getBackPhrase%}, which was never substituted).
 * <p>
 * Built by the engine per render/click from the {@link MenuSession}'s current
 * and previous {@link MenuSession.NavigationEntry}; getters follow the
 * {@code getX}/{@code hasX} naming getter chains expect.
 */
public final class MenuView {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[&§][0-9a-fk-orx]");

    private final String key;
    private final String title;
    private final boolean hasPrevious;
    private final String previousTitle;

    public MenuView(String key, String title, boolean hasPrevious, String previousTitle) {
        this.key = key;
        this.title = title;
        this.hasPrevious = hasPrevious;
        this.previousTitle = previousTitle;
    }

    /** Builds the view for the session's current entry ({@code title} is the assembled menu's title). */
    public static MenuView of(String key, String title, MenuSession session) {
        MenuSession.NavigationEntry previous = session != null ? session.previousEntry().orElse(null) : null;
        String previousTitle = previous == null ? null
                : previous.title() != null ? previous.title() : previous.key();
        return new MenuView(key, title, previous != null, previousTitle);
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public boolean hasPrevious() {
        return hasPrevious;
    }

    /** Previous menu's title with colour codes stripped, or {@code ""} when there is none. */
    public String getPreviousTitle() {
        return previousTitle != null ? stripColors(previousTitle) : "";
    }

    /** "Back" when {@code menu.back} would return to a previous menu, else "Exit" (it closes). */
    public String getBackLabel() {
        return hasPrevious ? "Back" : "Exit";
    }

    /** "Click here to go back to &lt;previous title&gt;" or "Click here to close this menu". */
    public String getBackHint() {
        return hasPrevious ? "Click here to go back to " + getPreviousTitle() : "Click here to close this menu";
    }

    static String stripColors(String text) {
        return COLOR_CODES.matcher(text).replaceAll("");
    }

    @Override
    public String toString() {
        return "MenuView[" + key + (hasPrevious ? ", previous=" + previousTitle : "") + "]";
    }
}
