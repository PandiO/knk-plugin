package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.commands.support.DurationParser;
import net.knightsandkings.knk.paper.user.UserAdminService;

/**
 * /knk user &lt;player&gt; info | coins|gems|xp set|add|remove &lt;amount&gt; [reason...]
 *          | group add|remove &lt;groupName&gt; [duration] | perm grant|revoke &lt;node&gt; [duration]
 * (developer request, 2026-09-25 - no in-game way to edit a player's rank/XP/coins/gems existed
 * before this; group/perm added in the same-day follow-up round closing the v1 rank-assignment
 * gap, see docs/specs/legacy/commands-v1.md's /user default|staff|builder|co-owner|owner).
 * Each property is gated on its own permission node (knk.admin.user.&lt;property&gt;) rather than
 * one umbrella, matching /knk gate's precedent of null top-level metadata permission + internal
 * per-action checks via sender.hasPermission - not KnkPermissible, since every other /knk admin
 * subcommand's permission checks (including gate's) are plain Bukkit nodes, not the REST-backed
 * grant system.
 * <p>
 * "set" (coins/gems/xp) is implemented as a computed delta against the target's current value
 * fetched fresh from knk-web-api (not the local cache, which can be stale/absent for an offline
 * player) - there is no separate "set absolute value" endpoint, only PUT /api/users/{id}/balances's
 * signed-delta shape, which this reuses for all three actions. That endpoint already rejects
 * underflow and, for a non-zero experience delta, resolves/audit-logs a consolidated title
 * change - this command shows that result via PromotionEffects rather than computing its own.
 * <p>
 * group/perm both work on offline targets the same way (resolveTarget's fresh-API-fetch, not
 * Bukkit.getPlayer), are multi-membership/multi-grant (add/remove one at a time, never touching
 * anything else the target holds), accept an optional [duration] token (DurationParser - "2h",
 * "90m", "3d", permanent if omitted), and are hierarchy-checked (RankHierarchy) - the acting
 * player's highest active PermissionGroup weight must exceed the target's, so staff can only
 * manage players ranked below themselves.
 * <p>
 * All of that logic lives in {@link UserAdminService} (InventoryMenu content port CP8) - the same
 * methods the in-game Player manager calls; this class only parses arguments.
 */
public class UserManagementCommand implements CommandExecutor {
    private static final List<String> PROPERTIES = List.of("info", "coins", "gems", "xp", "group", "perm");
    private static final List<String> BALANCE_ACTIONS = List.of("set", "add", "remove");
    private static final List<String> GROUP_ACTIONS = List.of("add", "remove");
    private static final List<String> PERM_ACTIONS = List.of("grant", "revoke");

    private final UserAdminService userAdminService;

    public UserManagementCommand(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        String targetName = args[0];
        String property = args[1].toLowerCase(Locale.ROOT);

        if (!PROPERTIES.contains(property)) {
            sendUsage(sender);
            return true;
        }

        if (property.equals("info")) {
            if (!hasAnyUserPermission(sender)) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to view player info.");
                return true;
            }
            userAdminService.resolveTarget(sender, targetName, target -> sendInfo(sender, target));
            return true;
        }

        if (!userAdminService.requireProperty(sender, property)) {
            return true;
        }

        if (property.equals("group")) {
            handleGroup(sender, targetName, args);
            return true;
        }
        if (property.equals("perm")) {
            handlePerm(sender, targetName, args);
            return true;
        }
        handleBalance(sender, targetName, property, args);
        return true;
    }

    private void handleBalance(CommandSender sender, String targetName, String property, String[] args) {
        if (args.length < 4) {
            sendUsage(sender);
            return;
        }

        String action = args[2].toLowerCase(Locale.ROOT);
        if (!BALANCE_ACTIONS.contains(action)) {
            sendUsage(sender);
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
            return;
        }
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "Amount must not be negative - use 'remove' to subtract.");
            return;
        }

        String reason = args.length > 4
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length))
            : "/knk user command by " + sender.getName();

        userAdminService.resolveTarget(sender, targetName,
            target -> userAdminService.changeBalance(sender, target, property, action, amount, reason));
    }

    private void handleGroup(CommandSender sender, String targetName, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk user <player> group add|remove <groupName> [duration]");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (!GROUP_ACTIONS.contains(action)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk user <player> group add|remove <groupName> [duration]");
            return;
        }
        String groupName = args[3];
        String durationToken = args.length > 4 ? args[4] : null;
        java.time.OffsetDateTime expiresAt;
        try {
            expiresAt = DurationParser.parse(durationToken);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
            return;
        }

        userAdminService.resolveTarget(sender, targetName,
            target -> userAdminService.changeGroupByName(sender, target, groupName, action.equals("add"), expiresAt));
    }

    private void handlePerm(CommandSender sender, String targetName, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk user <player> perm grant|revoke <node> [duration]");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (!PERM_ACTIONS.contains(action)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk user <player> perm grant|revoke <node> [duration]");
            return;
        }
        String node = args[3];
        String durationToken = args.length > 4 ? args[4] : null;
        java.time.OffsetDateTime expiresAt;
        try {
            expiresAt = DurationParser.parse(durationToken);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
            return;
        }

        userAdminService.resolveTarget(sender, targetName,
            target -> userAdminService.changePermission(sender, target, node, action.equals("grant"), expiresAt));
    }

    /** resolveTarget already delivers onFound on the main thread, so this sends directly. */
    private void sendInfo(CommandSender sender, UserSummary target) {
        sender.sendMessage(ChatColor.GOLD + "--- " + target.username() + " ---");
        sender.sendMessage(ChatColor.GRAY + "Coins: " + ChatColor.WHITE + target.coins()
            + ChatColor.GRAY + "  Gems: " + ChatColor.WHITE + target.gems());
        sender.sendMessage(ChatColor.GRAY + "XP: " + ChatColor.WHITE + target.experiencePoints()
            + ChatColor.GRAY + "  Title: " + ChatColor.WHITE + (target.titleName() != null ? target.titleName() : "-"));
    }

    private boolean hasAnyUserPermission(CommandSender sender) {
        return sender.hasPermission("knk.admin.user.info")
            || sender.hasPermission("knk.admin.user.coins")
            || sender.hasPermission("knk.admin.user.gems")
            || sender.hasPermission("knk.admin.user.xp")
            || sender.hasPermission("knk.admin.user.group")
            || sender.hasPermission("knk.admin.user.perm");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> info");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> coins|gems|xp set|add|remove <amount> [reason...]");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> group add|remove <groupName> [duration]");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> perm grant|revoke <node> [duration]");
        sender.sendMessage(ChatColor.GRAY + "  duration examples: 2h, 90m, 3d (omit for permanent)");
    }
}
