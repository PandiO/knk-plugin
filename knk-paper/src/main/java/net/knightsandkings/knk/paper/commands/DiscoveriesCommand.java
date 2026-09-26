package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.menu.content.DiscoveriesMenuFeature;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * /discoveries (alias /disc) - opens {@code discoveries.main}, the player's discovered places
 * (domain-discovery DESIGN.md §3.6/§3.7). Gated by {@code knk.discoveries} (default true) through
 * plugin.yml, like /menu's {@code knk.menu}. Opened from outside a menu, so its Back button reads
 * "Exit". {@link MenuService} is supplied lazily for the same reason as {@link MenuCommand}.
 */
public class DiscoveriesCommand implements CommandExecutor {

    private final Supplier<MenuService> menuService;

    public DiscoveriesCommand(Supplier<MenuService> menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open their discoveries.");
            return true;
        }
        MenuService service = menuService.get();
        if (service == null) {
            player.sendMessage(ChatColor.RED + "Menus are unavailable right now.");
            return true;
        }
        service.openMenu(player, DiscoveriesMenuFeature.MENU_KEY, MenuContextParams.EMPTY);
        return true;
    }
}
