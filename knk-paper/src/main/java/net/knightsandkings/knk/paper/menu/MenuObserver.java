package net.knightsandkings.knk.paper.menu;

import java.util.UUID;

import org.bukkit.entity.Player;

/**
 * Hooks {@link MenuService} and {@link MenuClickListener} call as players move through the
 * InventoryMenu (KNG-34 link 6, IMPLEMENTATION_PLAN.md §5.2): diagnostic telemetry
 * ({@code menu.opened}, {@code menu.action}, enhanced {@code menu.click}) and, in link 7, menu
 * funnels. Main thread. Every method has a no-op default; an observer that throws is logged and
 * skipped ({@link MenuObservers}), never allowed to break a menu.
 */
public interface MenuObserver {

    /** What happened to one action binding of a clicked item. */
    enum ActionOutcome { SUCCEEDED, DENIED, FAILED }

    /**
     * A menu was shown after an open (command, {@code menu.open} or a refresh-free navigation).
     *
     * @param parentMenuKey the menu the player navigated from; null when navigation started fresh
     */
    default void menuOpened(Player player, String menuKey, String parentMenuKey) { }

    /** {@code menu.back} re-opened {@code toMenuKey}. */
    default void menuBack(Player player, String fromMenuKey, String toMenuKey) { }

    /** The menu inventory was closed (by the player, {@code menu.back} at the root, or a quit). */
    default void menuClosed(UUID playerId, String menuKey) { }

    /** A visible, allowed item was clicked (before its conditions run). {@code itemKey} is the item id. */
    default void slotClicked(Player player, String menuKey, int slot, String itemKey, String clickType) { }

    /** One action binding ran, was refused by its conditions, or threw. */
    default void actionExecuted(Player player, String menuKey, String actionTypeId, int slot, ActionOutcome outcome) { }
}
