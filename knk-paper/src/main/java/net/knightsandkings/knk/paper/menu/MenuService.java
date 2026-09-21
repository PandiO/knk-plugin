package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.menu.MenuAssemblyException;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Paper-side orchestrator tying together the Phase 1 data access, the Phase 2
 * assembly/layout/pagination engine, and the live Bukkit Inventory - the
 * entry point commands and the click listener actually call.
 * <p>
 * {@link #openMenu} implements IMPLEMENTATION_PLAN.md Phase 2's async
 * rendering requirement directly: the whole fetch-assemble-compute pipeline
 * runs inside {@code runTaskAsynchronously}, and only the final Inventory
 * creation/population/open hops back to the main thread via {@code runTask} -
 * this is the fix for bug #6 (v1's {@code MenuItemBlink} mutating Inventory
 * off-thread); the fix is "don't touch Inventory off-thread", not "don't do
 * background work".
 */
public final class MenuService {

    private final Plugin plugin;
    private final Logger logger;
    private final MenuTemplatesDataAccess menuTemplatesDataAccess;
    private final MenuSessionRegistry sessionRegistry;
    private final OpenMenuContextRegistry openMenuContextRegistry;
    private final MenuRenderer renderer;
    private final Map<String, String> blockedMenus = new ConcurrentHashMap<>();

    public MenuService(
            Plugin plugin,
            MenuTemplatesDataAccess menuTemplatesDataAccess,
            MenuSessionRegistry sessionRegistry,
            OpenMenuContextRegistry openMenuContextRegistry,
            MenuRenderer renderer
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.menuTemplatesDataAccess = menuTemplatesDataAccess;
        this.sessionRegistry = sessionRegistry;
        this.openMenuContextRegistry = openMenuContextRegistry;
        this.renderer = renderer;
    }

    public void openMenu(Player player, String templateKey) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeMenu menu = loadAndAssemble(player, templateKey);
            if (menu == null) {
                return;
            }

            MenuSession session = sessionRegistry.open(player.getUniqueId());
            session.navigateTo(menu.key());

            renderAndOpen(player, menu, session);
        });
    }

    /**
     * Advances {@code sectionName}'s page. Re-renders into the already-open
     * Inventory when the player still has the menu open; otherwise re-fetches
     * and reopens it fresh with the new page. The fallback isn't an edge case
     * - it's the common case for this command specifically: vanilla Minecraft
     * doesn't let a player type a chat command while a custom Inventory
     * screen has focus, so by the time {@code /knk menu page next <section>}
     * actually runs, {@link MenuLifecycleListener}'s {@code InventoryCloseEvent}
     * handler has already cleared the {@link OpenMenuContext}. The
     * {@code MenuSession} (current menu key, per-section page) survives that
     * close regardless, which is exactly what makes the fallback possible.
     */
    public void nextPage(Player player, String sectionName) {
        changePage(player, sectionName, true);
    }

    public void previousPage(Player player, String sectionName) {
        changePage(player, sectionName, false);
    }

    private void changePage(Player player, String sectionName, boolean forward) {
        Optional<MenuSession> sessionOpt = sessionRegistry.get(player.getUniqueId());
        Optional<String> currentMenuKey = sessionOpt.flatMap(MenuSession::currentMenuKey);
        if (sessionOpt.isEmpty() || currentMenuKey.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You don't have a menu open.");
            return;
        }
        MenuSession session = sessionOpt.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeMenu menu = loadAndAssemble(player, currentMenuKey.get());
            if (menu == null) {
                return;
            }

            Optional<RuntimeMenuSection> section = menu.findSection(sectionName);
            if (section.isEmpty()) {
                String available = menu.sections().stream().map(RuntimeMenuSection::name)
                        .collect(Collectors.joining(", "));
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        ChatColor.YELLOW + "No section named '" + sectionName + "' in this menu. Available: " + available));
                return;
            }

            int sectionId = section.get().id();
            int currentTotalPages = section.get()
                    .resolveSlots(menu.totalSlots(), session.getPage(sectionId))
                    .totalPages();

            if (forward) {
                session.nextPage(sectionId, currentTotalPages);
            } else {
                session.previousPage(sectionId);
            }

            renderAndOpen(player, menu, session);
        });
    }

    /** Must be called off the main thread - blocks on {@link MenuRenderer#computeState}. */
    private void renderAndOpen(Player player, RuntimeMenu menu, MenuSession session) {
        MenuRenderResult result = renderer.computeState(menu, session, player);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Optional<OpenMenuContext> existing = openMenuContextRegistry.get(player.getUniqueId());
            if (existing.isPresent() && player.getOpenInventory().getTopInventory().equals(existing.get().inventory())) {
                // Still looking at it (e.g. a future click-driven page turn) - refresh in place.
                renderer.applyToInventory(existing.get().inventory(), result);
                existing.get().update(menu, result.itemsBySlot());
                return;
            }

            Inventory inventory = Bukkit.createInventory(null, menu.totalSlots(),
                    DisplayTextFormatter.toComponent(menu.title()));
            renderer.applyToInventory(inventory, result);
            player.openInventory(inventory);
            openMenuContextRegistry.register(player.getUniqueId(),
                    new OpenMenuContext(player, inventory, menu, result.itemsBySlot()));
        });
    }

    /** Must be called off the main thread. Returns null (having already messaged the player) on failure. */
    private RuntimeMenu loadAndAssemble(Player player, String templateKey) {
        String blockedReason = blockedMenus.get(templateKey);
        if (blockedReason != null) {
            failToOpen(player, templateKey, "blocked at startup validation: " + blockedReason, null);
            return null;
        }

        try {
            KnkMenuTemplate template = menuTemplatesDataAccess.getByKeyAsync(templateKey).join().value()
                    .orElseThrow(() -> new MenuAssemblyException("Menu template '" + templateKey + "' was not found"));
            return MenuTemplateAssembler.assemble(template);
        } catch (MenuAssemblyException e) {
            failToOpen(player, templateKey, e.getMessage(), null);
            return null;
        } catch (Exception e) {
            failToOpen(player, templateKey, e.getMessage(), e);
            return null;
        }
    }

    private void failToOpen(Player player, String templateKey, String reason, Exception cause) {
        if (cause != null) {
            logger.log(Level.WARNING, "Unexpected error loading menu '" + templateKey + "' for " + player.getName(), cause);
        } else {
            logger.warning("Refusing to open menu '" + templateKey + "' for " + player.getName() + ": " + reason);
        }
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(ChatColor.RED + "That menu is unavailable right now."));
    }

    /** Called from {@code PlayerQuitEvent} - closes gap #4 (v1's static per-player maps never cleared). */
    public void closeSession(UUID playerId) {
        openMenuContextRegistry.close(playerId);
        sessionRegistry.close(playerId);
    }

    /**
     * Marks a menu key as broken (IMPLEMENTATION_PLAN.md Phase 3,
     * {@link MenuDefinitionValidationRunner}, run once at plugin enable) so
     * every subsequent open attempt refuses immediately with a clear reason,
     * rather than re-discovering (and re-paying the reflection cost of) the
     * same failure on every player's click - DESIGN_REVIEW.md §1's "a menu
     * with unresolved bindings refuses to register" per-menu failure policy.
     */
    public void blockMenu(String templateKey, String reason) {
        blockedMenus.put(templateKey, reason != null ? reason : "failed startup validation");
    }

    /** Currently-blocked menu keys and why, for admin visibility (e.g. {@code /knk menu broken}). */
    public Map<String, String> blockedMenus() {
        return Map.copyOf(blockedMenus);
    }
}
