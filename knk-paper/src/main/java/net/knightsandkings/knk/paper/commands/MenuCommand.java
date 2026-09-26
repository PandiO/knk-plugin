package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.menu.content.HubMenuFeature;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * /menu - opens the InventoryMenu hub ({@code main}) for the player (content port CP1,
 * {@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §3). Opened from outside a menu, so
 * navigation starts fresh and the hub's Back button reads "Exit" (engine Phase 9 J2). Gated by
 * {@code knk.menu} (default true) through plugin.yml. {@link MenuService} is supplied lazily
 * because it is built after commands can be constructed and may be absent when the menu
 * engine failed to initialise.
 */
public class MenuCommand implements CommandExecutor {

    private final Supplier<MenuService> menuService;

    public MenuCommand(Supplier<MenuService> menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open the menu.");
            return true;
        }
        MenuService service = menuService.get();
        if (service == null) {
            player.sendMessage(ChatColor.RED + "Menus are unavailable right now.");
            return true;
        }
        service.openMenu(player, HubMenuFeature.HUB_KEY, MenuContextParams.EMPTY);
        return true;
    }
}
