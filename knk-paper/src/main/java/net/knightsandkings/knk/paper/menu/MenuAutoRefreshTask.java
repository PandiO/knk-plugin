package net.knightsandkings.knk.paper.menu;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * InventoryMenu Phase 9 (E4): the single synchronous repeating task behind
 * live repaint. Every tick it asks {@link MenuService#processDueRefreshes} to
 * re-render every open menu whose {@code AutoRefreshTicks} elapsed or that
 * {@code refreshOpenMenus} marked stale - all of them in the same tick
 * (batched), on the main thread. With no open auto-refreshing menus a tick
 * costs one pass over an empty/small map.
 * <p>
 * Scheduled by {@code KnKPlugin} with {@code runTaskTimer(plugin, 1L, 1L)}.
 */
public final class MenuAutoRefreshTask extends BukkitRunnable {

    private final MenuService menuService;

    public MenuAutoRefreshTask(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public void run() {
        menuService.processDueRefreshes();
    }
}
