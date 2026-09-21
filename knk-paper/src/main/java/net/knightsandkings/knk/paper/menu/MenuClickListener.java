package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Cancels every click inside a currently-open KnK menu Inventory (standard
 * custom-GUI hygiene - nothing should be able to pick up/rearrange/insert
 * items in a menu) and routes the click to the {@link RuntimeMenuItem} that
 * occupied the clicked slot in the last render.
 * <p>
 * Deliberately does not execute {@code item.actions()} - wiring
 * {@code ActionRegistry} is Phase 6's job (IMPLEMENTATION_PLAN.md). This
 * phase's click pipeline exists so the routing itself (identify which item
 * was clicked, respect DISABLED/HIDDEN, and reject a click actionPermission
 * denies) is in place and testable end-to-end against a live menu, without
 * pulling action execution forward.
 * <p>
 * The {@code actionPermission} check here is IMPLEMENTATION_PLAN.md Phase 4's
 * click-time defense-in-depth half of DESIGN_REVIEW.md §2.4: render-time
 * hiding/disabling (see {@code MenuItemBukkitMapper}) is never trusted alone,
 * in case of a stale client view or any other client/timing edge case.
 */
public final class MenuClickListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(MenuClickListener.class.getName());

    private final OpenMenuContextRegistry openMenuContextRegistry;

    public MenuClickListener(OpenMenuContextRegistry openMenuContextRegistry) {
        this.openMenuContextRegistry = openMenuContextRegistry;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Optional<OpenMenuContext> context = openMenuContextRegistry.get(player.getUniqueId());
        if (context.isEmpty()) {
            return;
        }

        Inventory menuInventory = context.get().inventory();
        if (!event.getView().getTopInventory().equals(menuInventory)) {
            // This player has our context registered but this click isn't in that
            // Inventory (e.g. a stale context from a menu that closed uncleanly) -
            // don't touch clicks that aren't actually ours.
            return;
        }

        // Never allow taking, rearranging, or shift-clicking items into a KnK menu.
        event.setCancelled(true);

        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || !clickedInventory.equals(menuInventory)) {
            return;
        }

        RuntimeMenuItem item = context.get().itemsBySlot().get(event.getSlot());
        if (item == null || item.displayMode() == MenuDisplayMode.DISABLED || item.displayMode() == MenuDisplayMode.HIDDEN) {
            return;
        }

        if (!item.isVisibleTo(player::hasPermission)) {
            // Defense in depth (DESIGN_REVIEW.md §2.4) - render-time exclusion
            // should already make this unreachable (the item wouldn't be in
            // itemsBySlot at all), but never trust that alone.
            return;
        }

        if (!item.isActionAllowedFor(player::hasPermission)) {
            LOGGER.fine(() -> "Player " + player.getName() + " lacks actionPermission '" + item.actionPermission()
                    + "' for menu item (id " + item.id() + "); click rejected");
            return;
        }

        LOGGER.fine(() -> "Player " + player.getName() + " clicked menu item (id " + item.id() + ", "
                + item.actions().size() + " action(s)) - execution deferred to Phase 6 (ActionRegistry not wired yet)");
    }
}
