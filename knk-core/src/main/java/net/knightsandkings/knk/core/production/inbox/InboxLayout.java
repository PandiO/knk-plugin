package net.knightsandkings.knk.core.production.inbox;

import java.util.List;

/**
 * The {@code /inbox} view (PandiO/knk-workspace#684): a double-chest-sized screen with the inbox's entries in the top five
 * rows (45 per page) and the bottom row for paging and the inbox's fill. The API sends every entry at once, so pages are
 * cut here. Clicks take like a storage terminal's ({@code TerminalLayout}): left a stack, right one, shift what fits.
 */
public final class InboxLayout {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private InboxLayout() {
    }

    /** Pages for {@code entryCount} entries; at least 1. */
    public static int pageCount(int entryCount) {
        return Math.max(1, (entryCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    /** {@code page} (1-based) clamped to the pages there are. */
    public static int clampPage(int page, int entryCount) {
        return Math.max(1, Math.min(page, pageCount(entryCount)));
    }

    /** The entries on {@code page} (1-based, clamped). */
    public static <T> List<T> page(List<T> entries, int page) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        int p = clampPage(page, entries.size());
        int from = (p - 1) * ENTRIES_PER_PAGE;
        return entries.subList(from, Math.min(entries.size(), from + ENTRIES_PER_PAGE));
    }

    /** The entry index within a page for a top-inventory slot, or -1 for the bottom row and outside. */
    public static int entryIndex(int slot) {
        return slot >= 0 && slot < ENTRIES_PER_PAGE ? slot : -1;
    }
}
