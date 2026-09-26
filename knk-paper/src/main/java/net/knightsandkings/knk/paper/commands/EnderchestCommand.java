package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.inventory.OfflineStorageAccess;

/**
 * {@code /enderchest [player]} (alias {@code /ec}) - port of v2's {@code Enderchest}
 * (docs/specs/legacy/commands-v2.md §1, KNG-9).
 * <ul>
 *   <li>{@code /ec} opens your own ender chest ({@value #NODE}).</li>
 *   <li>{@code /ec <player>}, {@code /ec open <player>} or {@code /ec check <player>} opens another
 *       player's ender chest ({@value #NODE_OPEN}); rank-checked, since the viewer can take items out.
 *       An online player's is the live one; an offline player's is their saved one, written back when
 *       the view closes (KNG-13, {@link OfflineStorageAccess} - v2's {@code check} was disabled since
 *       the 1.16→1.21 update).</li>
 * </ul>
 */
public class EnderchestCommand implements TabExecutor {

    public static final String NODE = "knk.enderchest";
    public static final String NODE_OPEN = "knk.enderchest.open";

    private static final List<String> SUBCOMMANDS = List.of("open", "check");

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;
    private final OfflineStorageAccess offlineStorage;

    public EnderchestCommand(PlayerCommandSupport support, TargetRankCheck rankCheck, OfflineStorageAccess offlineStorage) {
        this.support = support;
        this.rankCheck = rankCheck;
        this.offlineStorage = offlineStorage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player viewer = support.requirePlayer(sender);
        if (viewer == null) {
            return true;
        }

        String targetName;
        if (args.length == 0) {
            targetName = null;
        } else if (args.length == 1 && !SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            targetName = args[0];
        } else if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            targetName = args[1];
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /enderchest [player] | /enderchest open <player>");
            return true;
        }

        if (targetName == null || targetName.equalsIgnoreCase(viewer.getName())) {
            support.whenAllowed(viewer, NODE, () -> viewer.openInventory(viewer.getEnderChest()));
            return true;
        }

        support.whenAllowed(viewer, NODE_OPEN, () -> rankCheck.whenOutranks(viewer, targetName, target -> {
            Player online = support.onlinePlayer(target.username());
            if (online != null) {
                viewer.openInventory(online.getEnderChest());
                viewer.sendMessage(ChatColor.GRAY + "Opened " + ChatColor.WHITE + online.getName() + ChatColor.GRAY + "'s ender chest.");
            } else if (target.uuid() == null) {
                viewer.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
            } else {
                offlineStorage.open(viewer, target.uuid(), target.username(), OfflineStorageAccess.Kind.ENDER_CHEST);
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return support.completePlayers(args[0], SUBCOMMANDS.toArray(String[]::new));
        }
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return support.completePlayers(args[1]);
        }
        return Collections.emptyList();
    }
}
