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
 * {@code /inventory <open|clear> <player>} (aliases {@code /inv}, {@code /invsee}) - port of v2's
 * {@code Inventory} (docs/specs/legacy/commands-v2.md §7, KNG-9). Both subcommands were disabled in
 * v2 (its offline NMS lookup broke in the 1.16→1.21 update); here they work on online players:
 * <ul>
 *   <li>{@code open|see|check <player>} opens the player's live inventory ({@value #NODE_OPEN}).
 *       The viewer can move items in and out, as with v2's ender chest {@code open}.</li>
 *   <li>{@code clear <player> confirm} empties it, armour and off-hand included ({@value #NODE_CLEAR}).
 *       Without {@code confirm} it only says what would happen - there is no undo.</li>
 * </ul>
 * Both are rank-checked. Offline players are a separate issue (KNG-13). v2's dead {@code clear all}
 * branch isn't ported.
 */
public class InventoryCommand implements TabExecutor {

    public static final String NODE_OPEN = "knk.inventory.open";
    public static final String NODE_CLEAR = "knk.inventory.clear";

    // Offline support is KNG-13 (persistence approach still to be decided).
    static final String OFFLINE_HINT = "Offline inventories can't be viewed or cleared yet.";
    private static final List<String> OPEN_ALIASES = List.of("open", "see", "check");
    private static final String CLEAR = "clear";
    private static final String CONFIRM = "confirm";

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;

    public InventoryCommand(PlayerCommandSupport support, TargetRankCheck rankCheck) {
        this.support = support;
        this.rankCheck = rankCheck;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (OPEN_ALIASES.contains(sub) && args.length == 2) {
            handleOpen(sender, args[1]);
        } else if (CLEAR.equals(sub) && args.length <= 3) {
            handleClear(sender, args[1], args.length == 3 && CONFIRM.equalsIgnoreCase(args[2]));
        } else {
            sendUsage(sender);
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String targetName) {
        Player viewer = support.requirePlayer(sender);
        if (viewer == null) {
            return;
        }
        Player target = support.requireOnlinePlayer(sender, targetName, OFFLINE_HINT);
        if (target == null) {
            return;
        }
        if (PlayerCommandSupport.isSelf(viewer, target)) {
            viewer.sendMessage(ChatColor.RED + "That's your own inventory - press your inventory key.");
            return;
        }
        support.whenAllowed(viewer, NODE_OPEN, () -> rankCheck.whenOutranks(viewer, target, () -> {
            if (!target.isOnline()) {
                viewer.sendMessage(ChatColor.RED + target.getName() + " went offline.");
                return;
            }
            viewer.openInventory(target.getInventory());
            viewer.sendMessage(ChatColor.GRAY + "Opened " + ChatColor.WHITE + target.getName() + ChatColor.GRAY + "'s inventory.");
        }));
    }

    private void handleClear(CommandSender sender, String targetName, boolean confirmed) {
        Player target = support.requireOnlinePlayer(sender, targetName, OFFLINE_HINT);
        if (target == null) {
            return;
        }
        boolean self = PlayerCommandSupport.isSelf(sender, target);
        Runnable clear = () -> {
            if (!confirmed) {
                sender.sendMessage(ChatColor.YELLOW + "This empties " + (self ? "your" : target.getName() + "'s")
                        + " whole inventory, armour included, and can't be undone. Run "
                        + ChatColor.WHITE + "/inventory clear " + target.getName() + " confirm" + ChatColor.YELLOW + " to go ahead.");
                return;
            }
            if (!target.isOnline()) {
                sender.sendMessage(ChatColor.RED + target.getName() + " went offline.");
                return;
            }
            target.getInventory().clear();
            if (self) {
                sender.sendMessage(ChatColor.GREEN + "Your inventory was cleared.");
                return;
            }
            sender.sendMessage(ChatColor.GREEN + "Cleared " + ChatColor.WHITE + target.getName() + ChatColor.GREEN + "'s inventory.");
            target.sendMessage(ChatColor.RED + "Your inventory was cleared by " + ChatColor.WHITE + sender.getName() + ChatColor.RED + ".");
        };
        support.whenAllowed(sender, NODE_CLEAR, self ? clear : () -> rankCheck.whenOutranks(sender, target, clear));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /inventory open <player> | /inventory clear <player> confirm");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("open", "clear").filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (OPEN_ALIASES.contains(sub) || CLEAR.equals(sub))) {
            return support.completePlayers(args[1]);
        }
        if (args.length == 3 && CLEAR.equals(sub)) {
            return CONFIRM.startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of(CONFIRM) : Collections.emptyList();
        }
        return Collections.emptyList();
    }
}
