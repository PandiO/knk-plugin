package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;

/**
 * /knk user &lt;player&gt; info | coins|gems|xp set|add|remove &lt;amount&gt; [reason...]
 * (developer request, 2026-09-25 - no in-game way to edit a player's rank/XP/coins/gems existed
 * before this). Each numeric property is gated on its own permission node
 * (knk.admin.user.coins/.gems/.xp) rather than one umbrella, matching /knk gate's precedent of
 * null top-level metadata permission + internal per-action checks via sender.hasPermission -
 * not KnkPermissible, since every other /knk admin subcommand's permission checks (including
 * gate's) are plain Bukkit nodes, not the REST-backed grant system.
 * <p>
 * "set" is implemented as a computed delta against the target's current value fetched fresh from
 * knk-web-api (not the local cache, which can be stale/absent for an offline player) - there is
 * no separate "set absolute value" endpoint, only PUT /api/users/{id}/balances's signed-delta
 * shape, which this reuses for all three actions. That endpoint already rejects underflow and,
 * for a non-zero experience delta, resolves/audit-logs a title change itself - this command adds
 * no XP/title logic of its own.
 */
public class UserManagementCommand implements CommandExecutor {
    private static final List<String> PROPERTIES = List.of("info", "coins", "gems", "xp");
    private static final List<String> ACTIONS = List.of("set", "add", "remove");

    private final Plugin plugin;
    private final UsersDataAccess usersDataAccess;
    private final UsersCommandApi usersCommandApi;

    public UserManagementCommand(Plugin plugin, UsersDataAccess usersDataAccess, UsersCommandApi usersCommandApi) {
        this.plugin = plugin;
        this.usersDataAccess = usersDataAccess;
        this.usersCommandApi = usersCommandApi;
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
            resolveTarget(sender, targetName, target -> sendInfo(sender, target));
            return true;
        }

        String permission = "knk.admin.user." + property;
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage this player's " + property + ".");
            return true;
        }

        if (args.length < 4) {
            sendUsage(sender);
            return true;
        }

        String action = args[2].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            sendUsage(sender);
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
            return true;
        }
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "Amount must not be negative - use 'remove' to subtract.");
            return true;
        }

        String reason = args.length > 4
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length))
            : "/knk user command by " + sender.getName();

        int finalAmount = amount;
        resolveTarget(sender, targetName, target -> {
            int current = switch (property) {
                case "coins" -> target.coins();
                case "gems" -> target.gems();
                default -> target.experiencePoints();
            };
            int delta = switch (action) {
                case "set" -> finalAmount - current;
                case "remove" -> -finalAmount;
                default -> finalAmount;
            };
            if (delta == 0) {
                sender.sendMessage(ChatColor.YELLOW + target.username() + "'s " + property + " is already " + finalAmount + ".");
                return;
            }

            int coinsDelta = property.equals("coins") ? delta : 0;
            int gemsDelta = property.equals("gems") ? delta : 0;
            int experienceDelta = property.equals("xp") ? delta : 0;

            usersCommandApi.adjustBalancesById(target.id(), coinsDelta, gemsDelta, experienceDelta, reason)
                .thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
                    String verb = delta > 0 ? "Increased" : "Decreased";
                    sender.sendMessage(ChatColor.GREEN + verb + " " + target.username() + "'s " + property
                        + " by " + Math.abs(delta) + " (now " + (current + delta) + ").");
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex)));
                    return null;
                });
        });
        return true;
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
            || sender.hasPermission("knk.admin.user.xp");
    }

    /**
     * Resolves targetName to a live UserSummary via the API directly (not FetchPolicy.CACHE_FIRST)
     * so an offline player's current balances are still accurate, and reports a clear "not found"
     * message on the calling sender rather than a raw 404. onFound always runs on the main thread.
     */
    private void resolveTarget(CommandSender sender, String targetName, java.util.function.Consumer<UserSummary> onFound) {
        usersDataAccess.getByUsernameAsync(targetName).thenAccept(result ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!result.isSuccess() || result.value().isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
                    return;
                }
                onFound.accept(result.value().get());
            })
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed to look up '" + targetName + "': " + describeError(ex)));
            return null;
        });
    }

    private String describeError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ApiException apiEx) {
                if (apiEx.getResponseBody() != null && !apiEx.getResponseBody().isEmpty()) {
                    return apiEx.getResponseBody();
                }
                return apiEx.getMessage();
            }
            cause = cause.getCause();
        }
        return ex.getMessage();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> info");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> coins|gems|xp set|add|remove <amount> [reason...]");
    }
}
