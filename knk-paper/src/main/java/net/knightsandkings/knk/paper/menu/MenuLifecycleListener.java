package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuTransitionTracker;
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
 * Alpha cross-play finding 2026-10-24: while a Bedrock player's menu switch is
 * in progress ({@link MenuService#configureBedrockTransitions}), a close the
 * client sends is the stale close of the previous Bedrock form - ignored before
 * the next menu opens, and answered by showing the new menu again when it
 * arrives just after. Java players never have a transition in progress.
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

        MenuTransitionTracker.CloseVerdict verdict = event.getReason() == InventoryCloseEvent.Reason.PLAYER
                ? menuService.onClientClose(player.getUniqueId())
                : MenuTransitionTracker.CloseVerdict.NORMAL;
        if (verdict == MenuTransitionTracker.CloseVerdict.STALE_IGNORE) {
            return;
        }

        openMenuContextRegistry.get(player.getUniqueId()).ifPresent(context -> {
            if (context.inventory().equals(event.getInventory())) {
                if (verdict == MenuTransitionTracker.CloseVerdict.STALE_REOPEN) {
                    menuService.reopenAfterStaleClose(player, context);
                    return;
                }
                // InventoryMenu Phase 9 (E4): a closed menu is no longer repainted. Before the context
                // goes: the observers read the closed menu's key from it (KNG-34 smoke test finding 12 -
                // closed the other way round, every Escape reached the funnels without a key).
                menuService.onMenuInventoryClosed(player.getUniqueId());
                openMenuContextRegistry.close(player.getUniqueId());
            }
        });
    }
}
