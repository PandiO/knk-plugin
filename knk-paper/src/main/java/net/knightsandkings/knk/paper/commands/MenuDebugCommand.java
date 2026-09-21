package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Dev-harness command for exercising the Phase 2 rendering engine end-to-end
 * against a real server ({@code /knk menu open <key>},
 * {@code /knk menu page next|prev <sectionName>}) - there's no real menu
 * content to trigger this from yet (porting v1's screens and Kits/Sieges is
 * separate, later work per IMPLEMENTATION_PLAN.md's "explicitly out of
 * scope" section), so this is the only way to actually open a menu and
 * verify assembly/layout/pagination/async-rendering on a live dev server
 * rather than stopping at unit tests.
 */
public class MenuDebugCommand implements CommandExecutor {

    private final MenuService menuService;

    public MenuDebugCommand(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu open <key> | /knk menu page next|prev <sectionName>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "open" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu open <key>");
                    return true;
                }
                menuService.openMenu(player, args[1]);
            }
            case "page" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu page next|prev <sectionName>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("next")) {
                    menuService.nextPage(player, args[2]);
                } else if (args[1].equalsIgnoreCase("prev") || args[1].equalsIgnoreCase("previous")) {
                    menuService.previousPage(player, args[2]);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu page next|prev <sectionName>");
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand. Use: open, page");
        }

        return true;
    }
}
