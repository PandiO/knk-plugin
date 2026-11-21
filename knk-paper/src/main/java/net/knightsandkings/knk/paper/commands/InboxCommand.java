package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.production.inbox.InboxModule;

/**
 * {@code /inbox} (PandiO/knk-workspace#684): opens the player's reward inbox. Every player may use it (their own inbox;
 * the API answers for that player only), so it has no node. With {@code production.enabled} off it says the inbox is off.
 */
public final class InboxCommand implements TabExecutor {

    public static final String OFF = "The reward inbox is not switched on on this server.";

    private final Supplier<InboxModule> module;

    public InboxCommand(Supplier<InboxModule> module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players have a reward inbox.");
            return true;
        }
        InboxModule inbox = module.get();
        if (inbox == null) {
            player.sendMessage(ChatColor.GRAY + OFF);
            return true;
        }
        inbox.open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
