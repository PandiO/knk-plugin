package net.knightsandkings.knk.paper.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Owns the two lifecycle transitions IMPLEMENTATION_PLAN.md Phase 2 requires:
 * <ul>
 *   <li>{@link PlayerQuitEvent}: clears this player's {@code MenuSession}
 *       (core) and {@link OpenMenuContext} (paper) entirely - the actual fix
 *       for reconciliation gap #4 (v1's static per-player maps that were
 *       never cleared and grew without bound).</li>
 *   <li>{@link InventoryCloseEvent}: clears only the paper-side
 *       {@link OpenMenuContext} (the live Inventory reference) when the
 *       player closes the menu screen without quitting - the core
 *       {@code MenuSession} (navigation history, per-section page, dirty
 *       flag) deliberately survives this, so reopening a menu later resumes
 *       where they left off, the same way it would across any other menu
 *       navigation.</li>
 * </ul>
 */
public final class MenuLifecycleListener implements Listener {

    private final MenuService menuService;
    private final OpenMenuContextRegistry openMenuContextRegistry;

    public MenuLifecycleListener(MenuService menuService, OpenMenuContextRegistry openMenuContextRegistry) {
        this.menuService = menuService;
        this.openMenuContextRegistry = openMenuContextRegistry;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menuService.closeSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        openMenuContextRegistry.get(player.getUniqueId()).ifPresent(context -> {
            if (context.inventory().equals(event.getInventory())) {
                openMenuContextRegistry.close(player.getUniqueId());
                // InventoryMenu Phase 9 (E4): a closed menu is no longer repainted.
                menuService.onMenuInventoryClosed(player.getUniqueId());
            }
        });
    }
}
