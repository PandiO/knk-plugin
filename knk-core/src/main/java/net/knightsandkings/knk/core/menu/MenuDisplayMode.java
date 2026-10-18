package net.knightsandkings.knk.core.menu;

/**
 * FR-2.4.1 display modes, mirrors knk-web-api's {@code MenuDisplayMode}
 * (IMPLEMENTATION_PLAN.md's NORMAL/DISABLED/HIGHLIGHT/HIDDEN naming, not the
 * older requirements doc's DISPLAY/HIDE/DISABLED/HIGHLIGHT).
 * <p>
 * {@link #BLINK} (KNG-125, FR-2.6.1, the v1/v2 item blinker): rendered like
 * {@link #HIGHLIGHT}, and while the menu is open the slot alternates with a
 * yellow glass pane carrying the item's name and lore (see {@link MenuBlink}).
 * Clickable like NORMAL/HIGHLIGHT. Plugin-only for now: knk-web-api's enum has
 * no {@code Blink} column value yet, so templates select it through a
 * {@code DisplayMode} variable binding (e.g. {@code $statsvis.getCombatMode$},
 * or the literal {@code BLINK}).
 */
public enum MenuDisplayMode {
    NORMAL,
    DISABLED,
    HIGHLIGHT,
    HIDDEN,
    BLINK
}
