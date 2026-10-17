package net.knightsandkings.knk.core.menu;

/**
 * KNG-125 (FR-2.6.1, the v1/v2 {@code MenuItemBlink}): the Bukkit-free timing of the item blinker.
 * A {@link MenuDisplayMode#BLINK} item shows its own {@code ItemStack} for one interval, then a
 * yellow glass pane with the same name and lore for the next, and so on while the menu is open.
 * <p>
 * The phase is a pure function of the tick the menu was opened and the current tick, so the
 * blinker keeps no per-slot timers: knk-paper's {@code MenuItemBlinker} asks every tick (from
 * {@code MenuAutoRefreshTask}, main thread) which phase each blinking slot should be in and swaps
 * only the slots whose phase changed. A re-render keeps the rhythm.
 * <p>
 * The interval is {@value #DEFAULT_INTERVAL_TICKS} ticks (v1/v2's value) unless the item has a
 * {@code BlinkTicks} variable binding ({@link MenuItemPresentation#BLINK_TICKS}); it is clamped to
 * {@value #MIN_INTERVAL_TICKS}-{@value #MAX_INTERVAL_TICKS}.
 */
public final class MenuBlink {

    public static final int DEFAULT_INTERVAL_TICKS = 10;
    public static final int MIN_INTERVAL_TICKS = 2;
    public static final int MAX_INTERVAL_TICKS = 1200;

    private MenuBlink() {
    }

    /** The interval to use for a resolved {@code BlinkTicks} value (null: the default), clamped. */
    public static int intervalTicks(Integer bound) {
        if (bound == null) {
            return DEFAULT_INTERVAL_TICKS;
        }
        return Math.max(MIN_INTERVAL_TICKS, Math.min(MAX_INTERVAL_TICKS, bound));
    }

    /**
     * Whether the yellow pane (true) or the item itself (false) is shown at {@code nowTick} for a
     * blinker that started at {@code startTick}: the item for the first interval, the pane for the
     * second, alternating after that. Before {@code startTick} the item is shown.
     */
    public static boolean flashShown(long startTick, long nowTick, int intervalTicks) {
        long elapsed = nowTick - startTick;
        if (elapsed < 0) {
            return false;
        }
        int interval = intervalTicks(intervalTicks);
        return (elapsed / interval) % 2 == 1;
    }
}
