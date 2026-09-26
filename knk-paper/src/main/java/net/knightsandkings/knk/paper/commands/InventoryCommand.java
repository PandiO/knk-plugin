package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.inventory.OfflineStorageAccess;

/**
 * {@code /inventory <open|clear> <player>} (aliases {@code /inv}, {@code /invsee}) - port of v2's
 * {@code Inventory} (docs/specs/legacy/commands-v2.md §7, KNG-9; offline players KNG-13).
 * <ul>
 *   <li>{@code open|see|check <player>} opens the player's inventory ({@value #NODE_OPEN}): an online
 *       player's live inventory, an offline player's saved one (written back when the view closes,
 *       {@link OfflineStorageAccess}). The viewer can move items in and out.</li>
 *   <li>{@code clear <player> confirm} empties it, armour and off-hand included ({@value #NODE_CLEAR}),
 *       online or offline. Without {@code confirm} it only says what would happen - there is no undo.</li>
 * </ul>
 * Both are rank-checked, except clearing your own. v2's dead {@code clear all} branch isn't ported.
 */
public class InventoryCommand implements TabExecutor {

    public static final String NODE_OPEN = "knk.inventory.open";
    public static final String NODE_CLEAR = "knk.inventory.clear";

    private static final List<String> OPEN_ALIASES = List.of("open", "see", "check");
    private static final String CLEAR = "clear";
    private static final String CONFIRM = "confirm";

    private final PlayerCommandSupport support;
    private final TargetRankCheck rankCheck;
    private final OfflineStorageAccess offlineStorage;

    public InventoryCommand(PlayerCommandSupport support, TargetRankCheck rankCheck, OfflineStorageAccess offlineStorage) {
        this.support = support;
        this.rankCheck = rankCheck;
        this.offlineStorage = offlineStorage;
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
        if (targetName.equalsIgnoreCase(viewer.getName())) {
            viewer.sendMessage(ChatColor.RED + "That's your own inventory - press your inventory key.");
            return;
        }
        support.whenAllowed(viewer, NODE_OPEN, () -> rankCheck.whenOutranks(viewer, targetName, target -> {
            Player online = support.onlinePlayer(target.username());
            if (online != null) {
                viewer.openInventory(online.getInventory());
                viewer.sendMessage(ChatColor.GRAY + "Opened " + ChatColor.WHITE + online.getName() + ChatColor.GRAY + "'s inventory.");
            } else if (target.uuid() == null) {
                viewer.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
            } else {
                offlineStorage.open(viewer, target.uuid(), target.username(), OfflineStorageAccess.Kind.INVENTORY);
            }
        }));
    }

    private void handleClear(CommandSender sender, String targetName, boolean confirmed) {
        if (!confirmed) {
            boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(targetName);
            sender.sendMessage(ChatColor.YELLOW + "This empties " + (self ? "your" : targetName + "'s")
                    + " whole inventory, armour included, and can't be undone. Run "
                    + ChatColor.WHITE + "/inventory clear " + targetName + " confirm" + ChatColor.YELLOW + " to go ahead.");
            return;
        }
        if (sender instanceof Player player && player.getName().equalsIgnoreCase(targetName)) {
            support.whenAllowed(sender, NODE_CLEAR, () -> {
                player.getInventory().clear();
                sender.sendMessage(ChatColor.GREEN + "Your inventory was cleared.");
            });
            return;
        }
        support.whenAllowed(sender, NODE_CLEAR, () -> rankCheck.whenOutranks(sender, targetName, target -> clearOther(sender, targetName, target)));
    }

    private void clearOther(CommandSender sender, String targetName, UserSummary target) {
        Player online = support.onlinePlayer(target.username());
        if (online != null) {
            online.getInventory().clear();
            sender.sendMessage(ChatColor.GREEN + "Cleared " + ChatColor.WHITE + online.getName() + ChatColor.GREEN + "'s inventory.");
            online.sendMessage(ChatColor.RED + "Your inventory was cleared by " + ChatColor.WHITE + sender.getName() + ChatColor.RED + ".");
        } else if (target.uuid() == null) {
            sender.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
        } else {
            offlineStorage.clearInventory(sender, target.uuid(), target.username());
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /inventory open <player> | /inventory clear <player> confirm");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("open", "clear").filter(s -> s.startsWith(prefix)).toList();
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
