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

/**
 * {@code /enderchest [player]} (alias {@code /ec}) - port of v2's {@code Enderchest}
 * (docs/specs/legacy/commands-v2.md §1, KNG-9).
 * <ul>
 *   <li>{@code /ec} opens your own ender chest ({@value #NODE}).</li>
 *   <li>{@code /ec <player>} or {@code /ec open <player>} opens an online player's live ender chest
 *       ({@value #NODE_OPEN}); rank-checked, since the viewer can take items out.</li>
 *   <li>{@code /ec check <player>} (v2's offline lookup, disabled there since the 1.16→1.21 update)
 *       works for online players only; offline players are a separate issue (KNG-13).</li>
 * </ul>
 */
public class EnderchestCommand implements TabExecutor {

    public static final String NODE = "knk.enderchest";
    public static final String NODE_OPEN = "knk.enderchest.open";

    // Offline support is KNG-13 (persistence approach still to be decided).
    static final String OFFLINE_HINT = "Offline ender chests can't be viewed yet.";
    private static final List<String> SUBCOMMANDS = List.of("open", "check");

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;

    public EnderchestCommand(PlayerCommandSupport support, TargetRankCheck rankCheck) {
        this.support = support;
        this.rankCheck = rankCheck;
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

        if (targetName == null) {
            support.whenAllowed(viewer, NODE, () -> viewer.openInventory(viewer.getEnderChest()));
            return true;
        }

        Player target = support.requireOnlinePlayer(sender, targetName, OFFLINE_HINT);
        if (target == null) {
            return true;
        }
        if (PlayerCommandSupport.isSelf(viewer, target)) {
            support.whenAllowed(viewer, NODE, () -> viewer.openInventory(viewer.getEnderChest()));
            return true;
        }
        support.whenAllowed(viewer, NODE_OPEN, () -> rankCheck.whenOutranks(viewer, target, () -> {
            if (!target.isOnline()) {
                viewer.sendMessage(ChatColor.RED + target.getName() + " went offline.");
                return;
            }
            viewer.openInventory(target.getEnderChest());
            viewer.sendMessage(ChatColor.GRAY + "Opened " + ChatColor.WHITE + target.getName() + ChatColor.GRAY + "'s ender chest.");
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
