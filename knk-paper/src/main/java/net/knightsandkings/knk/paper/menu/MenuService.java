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

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            RuntimeMenu menu;
            try {
                KnkMenuTemplate template = menuTemplatesDataAccess.getByKeyAsync(templateKey).join().value()
                        .orElseThrow(() -> new MenuAssemblyException("Menu template '" + templateKey + "' was not found"));
                menu = MenuTemplateAssembler.assemble(template);
            } catch (MenuAssemblyException e) {
                failToOpen(player, templateKey, e.getMessage(), null);
                return;
            } catch (Exception e) {
                failToOpen(player, templateKey, e.getMessage(), e);
                return;
            }

            MenuSession session = sessionRegistry.open(player.getUniqueId());
            session.navigateTo(menu.key());

            RuntimeMenu finalMenu = menu;
            MenuRenderResult result = renderer.computeState(finalMenu, session);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory inventory = Bukkit.createInventory(null, finalMenu.totalSlots(),
                        DisplayTextFormatter.toComponent(finalMenu.title()));
                renderer.applyToInventory(inventory, result);
                player.openInventory(inventory);
                openMenuContextRegistry.register(player.getUniqueId(),
                        new OpenMenuContext(player, inventory, finalMenu, result.itemsBySlot()));
            });
        });
    }

    /** Advances {@code sectionName}'s page and re-renders into the already-open Inventory. */
    public void nextPage(Player player, String sectionName) {
        changePage(player, sectionName, true);
    }

    public void previousPage(Player player, String sectionName) {
        changePage(player, sectionName, false);
    }

    private void changePage(Player player, String sectionName, boolean forward) {
        Optional<OpenMenuContext> context = openMenuContextRegistry.get(player.getUniqueId());
        Optional<MenuSession> session = sessionRegistry.get(player.getUniqueId());
        if (context.isEmpty() || session.isEmpty()) {
            return;
        }

        RuntimeMenu menu = context.get().menu();
        Optional<RuntimeMenuSection> section = menu.findSection(sectionName);
        if (section.isEmpty()) {
            return;
        }

        int sectionId = section.get().id();
        int currentTotalPages = section.get()
                .resolveSlots(menu.totalSlots(), session.get().getPage(sectionId))
                .totalPages();

        if (forward) {
            session.get().nextPage(sectionId, currentTotalPages);
        } else {
            session.get().previousPage(sectionId);
        }

        refresh(player, menu, session.get(), context.get());
    }

    private void refresh(Player player, RuntimeMenu menu, MenuSession session, OpenMenuContext context) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MenuRenderResult result = renderer.computeState(menu, session);
            Bukkit.getScheduler().runTask(plugin, () -> {
                renderer.applyToInventory(context.inventory(), result);
                context.update(menu, result.itemsBySlot());
            });
        });
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
}
