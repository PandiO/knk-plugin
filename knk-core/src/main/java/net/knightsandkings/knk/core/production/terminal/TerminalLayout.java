package net.knightsandkings.knk.core.production.terminal;

/**
 * The terminal view (production DESIGN § 6.2, PandiO/knk-workspace#74): a double-chest-sized screen, the storage's entries
 * in the top five rows (45 per page), the bottom row for paging and the storage's fill. Clicks on an entry take; clicks
 * on the player's own inventory put.
 */
public final class TerminalLayout {

    public static final int SIZE = 54;
    public static final int ENTRIES_PER_PAGE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    /** How a slot was clicked. */
    public enum Click { LEFT, RIGHT, SHIFT }

    private TerminalLayout() {
    }

    /** The entry index on the page for a slot of the top inventory, or -1 for the bottom row and outside. */
    public static int entryIndex(int slot) {
        return slot >= 0 && slot < ENTRIES_PER_PAGE ? slot : -1;
    }

    /**
     * Units a take asks for: left click a stack, right click one, shift click as many as fit in the player's inventory.
     * Never more than the entry holds; 0 when nothing fits.
     *
     * @param maxStack the item's stack size (1 for unstackables)
     * @param room units of this item the player's inventory can still hold
     */
    public static long takeAmount(Click click, long available, int maxStack, long room) {
        if (available <= 0) {
            return 0;
        }
        long wanted = switch (click) {
            case LEFT -> Math.max(1, maxStack);
            case RIGHT -> 1;
            case SHIFT -> room;
        };
        return Math.max(0, Math.min(Math.min(wanted, available), Math.max(0, room)));
    }

    /** Units a put sends: the whole stack, or one for a right click. */
    public static int putAmount(Click click, int stackAmount) {
        if (stackAmount <= 0) {
            return 0;
        }
        return click == Click.RIGHT ? 1 : stackAmount;
    }

    /** "Page 2 of 5". */
    public static String pageText(TerminalPage page) {
        return "Page " + page.page() + " of " + page.pageCount();
    }

    /** "40 of 1,000 units (4%)" or "40 units" without a cap. */
    public static String fillText(long totalUnits, Long maxUnits) {
        if (maxUnits == null || maxUnits <= 0) {
            return String.format(java.util.Locale.ROOT, "%,d units", totalUnits);
        }
        return String.format(java.util.Locale.ROOT, "%,d of %,d units (%d%%)", totalUnits, maxUnits,
                Math.round(100.0 * totalUnits / maxUnits));
    }
}
