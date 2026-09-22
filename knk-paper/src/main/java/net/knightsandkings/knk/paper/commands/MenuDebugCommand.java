package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Dev-harness command for exercising the rendering engine end-to-end against
 * a real server ({@code /knk menu open <key>},
 * {@code /knk menu page next|prev <sectionName>},
 * {@code /knk menu search <sectionName> [clear]},
 * {@code /knk menu filter <sectionName> <facetKey> [clear]},
 * {@code /knk menu broken}) - there's no real menu content to trigger this
 * from yet (porting v1's screens and Kits/Sieges is separate, later work per
 * IMPLEMENTATION_PLAN.md's "explicitly out of scope" section), so this is
 * the only way to actually open a menu and verify assembly/layout/
 * pagination/variable-resolution/search-filter/async-rendering on a live dev
 * server rather than stopping at unit tests.
 * <p>
 * IMPLEMENTATION_PLAN.md Phase 7 / QOL_BUGFIX_BACKLOG.md item 8: {@code page}/
 * {@code search}/{@code filter} are no longer the only way to drive these -
 * {@code example.presets}' real clickable Next/Previous, Search, and Filter-
 * cycle items now exist and go through the exact same {@link MenuService}
 * methods these subcommands call. This command stays registered as the
 * DESIGN_REVIEW.md §2.5-decided optional command fallback (never the primary
 * path), and remains useful for scripting/macros and for exercising a section
 * that hasn't been given real buttons yet.
 */
public class MenuDebugCommand implements CommandExecutor {

    private final MenuService menuService;

    public MenuDebugCommand(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("broken")) {
            reportBrokenMenus(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + USAGE);
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
            case "search" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu search <sectionName> [clear]");
                    return true;
                }
                String sectionName = args[1];
                if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
                    menuService.clearSearch(player, sectionName);
                } else {
                    menuService.promptSearch(player, sectionName);
                }
            }
            case "filter" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk menu filter <sectionName> <facetKey> [clear]");
                    return true;
                }
                String sectionName = args[1];
                String facetKey = args[2];
                if (args.length >= 4 && args[3].equalsIgnoreCase("clear")) {
                    menuService.clearFilter(player, sectionName, facetKey);
                } else {
                    menuService.promptFilter(player, sectionName, facetKey);
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW
                    + "Unknown subcommand '" + String.join(" ", args) + "'. " + USAGE);
        }

        return true;
    }

    private static final String USAGE = "Usage: /knk menu open <key> | /knk menu page next|prev <sectionName> | "
            + "/knk menu search <sectionName> [clear] | /knk menu filter <sectionName> <facetKey> [clear] | /knk menu broken";

    /**
     * Lists menus {@link net.knightsandkings.knk.paper.menu.MenuDefinitionValidationRunner}
     * blocked at plugin enable - DESIGN_REVIEW.md §1's "ideally, an admin
     * command to list currently-broken menus at any time".
     */
    private void reportBrokenMenus(CommandSender sender) {
        var blocked = menuService.blockedMenus();
        if (blocked.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "No menus are currently blocked.");
            return;
        }
        sender.sendMessage(ChatColor.RED + "" + blocked.size() + " menu(s) blocked since startup validation:");
        blocked.forEach((key, reason) -> sender.sendMessage(ChatColor.YELLOW + " - " + key + ": " + ChatColor.GRAY + reason));
    }
}
