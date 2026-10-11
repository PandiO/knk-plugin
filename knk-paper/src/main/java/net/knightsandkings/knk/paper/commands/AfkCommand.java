package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.statistics.StatisticsService;

/**
 * {@code /afk} (player statistics, DESIGN.md §F.2): toggles the player's AFK state - AFK time is
 * counted from the command on; any activity or a second {@code /afk} ends it. Registered only while
 * {@code statistics.enabled}; {@code statistics.afk.command-enabled: false} (or AFK detection off)
 * answers "AFK is disabled".
 */
public final class AfkCommand implements TabExecutor {

    private final StatisticsService service;
    private final boolean enabled;

    public AfkCommand(StatisticsService service, boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can be AFK.");
            return true;
        }
        if (!enabled) {
            player.sendMessage(ChatColor.RED + "AFK is disabled.");
            return true;
        }
        Optional<Boolean> afk = service.toggleAfk(player);
        if (afk.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
