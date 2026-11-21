package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.production.shop.ShopService;

/**
 * {@code /knk shop <shop id>} (PandiO/knk-workspace#687, node {@value #PERMISSION}): opens a shop's menu for the sender,
 * for testing until shopkeepers (P11, #249) open it. With {@code production.enabled: false} it answers that production is
 * off.
 */
public final class ShopAdminCommand {

    public static final String PERMISSION = ProductionAdminCommand.PERMISSION;

    private final Supplier<ShopService> shops;

    public ShopAdminCommand(Supplier<ShopService> shops) {
        this.shops = shops;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "shop",
                "Open a shop's menu (buy, item information, sell) for testing until shopkeepers arrive (production.enabled)",
                "/knk shop <shop id>",
                PERMISSION,
                List.of("/knk shop 12"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        ShopService service = shops.get();
        if (service == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open a shop.");
            return true;
        }
        Integer shopId = args.length == 1 ? parse(args[0]) : null;
        if (shopId == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
            return true;
        }
        player.sendMessage(ChatColor.GRAY + "Opening shop #" + shopId + "...");
        service.open(player, shopId);
        return true;
    }

    static Integer parse(String value) {
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
