package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.commands.support.DurationParser;
import net.knightsandkings.knk.paper.commands.support.PromotionEffects;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;
import net.knightsandkings.knk.paper.modes.ModeService;

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
 */
public class UserManagementCommand implements CommandExecutor {
    private static final List<String> PROPERTIES = List.of("info", "coins", "gems", "xp", "group", "perm");
    private static final List<String> BALANCE_ACTIONS = List.of("set", "add", "remove");
    private static final List<String> GROUP_ACTIONS = List.of("add", "remove");
    private static final List<String> PERM_ACTIONS = List.of("grant", "revoke");

    private final Plugin plugin;
    private final UsersDataAccess usersDataAccess;
    private final UsersCommandApi usersCommandApi;
    private final PermissionGroupsQueryApi permissionGroupsQueryApi;
    private final RankHierarchy rankHierarchy;
    private final ModeService modeService;

    public UserManagementCommand(
        Plugin plugin,
        UsersDataAccess usersDataAccess,
        UsersCommandApi usersCommandApi,
        PermissionGroupsQueryApi permissionGroupsQueryApi,
        RankHierarchy rankHierarchy,
        ModeService modeService
    ) {
        this.plugin = plugin;
        this.usersDataAccess = usersDataAccess;
        this.usersCommandApi = usersCommandApi;
        this.permissionGroupsQueryApi = permissionGroupsQueryApi;
        this.rankHierarchy = rankHierarchy;
        this.modeService = modeService;
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
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    String verb = delta > 0 ? "Increased" : "Decreased";
                    sender.sendMessage(ChatColor.GREEN + verb + " " + target.username() + "'s " + property
                        + " by " + Math.abs(delta) + " (now " + (current + delta) + ").");
                    Player targetPlayer = Bukkit.getPlayerExact(target.username());
                    if (targetPlayer != null && result.titleChange() != null) {
                        PromotionEffects.show(targetPlayer, result.titleChange());
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex)));
                    return null;
                });
        });
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

        resolveTarget(sender, targetName, target -> withRankCheck(sender, target, () ->
            permissionGroupsQueryApi.list().thenAccept(groups -> {
                PermissionGroupSummary group = groups.stream()
                    .filter(g -> g.name().equalsIgnoreCase(groupName))
                    .findFirst()
                    .orElse(null);
                if (group == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "No PermissionGroup named '" + groupName + "'."));
                    return;
                }
                CompletableFuture<Void> action_ = action.equals("add")
                    ? usersCommandApi.addGroupMembership(target.id(), group.id(), expiresAt)
                    : usersCommandApi.removeGroupMembership(target.id(), group.id());
                boolean adding = action.equals("add");
                action_.thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
                    String verb = adding ? "Added" : "Removed";
                    String durationSuffix = adding ? (expiresAt != null ? " (expires " + expiresAt + ")" : " (permanent)") : "";
                    sender.sendMessage(ChatColor.GREEN + verb + " " + target.username() + "'s membership in "
                        + group.name() + durationSuffix + ".");
                    notifyGroupChange(target, group, adding);
                    refreshTargetVisibility(target);
                })).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex)));
                    return null;
                });
            }).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed to list groups: " + describeError(ex)));
                return null;
            })
        ));
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
        java.time.OffsetDateTime finalExpiresAt = expiresAt;

        resolveTarget(sender, targetName, target -> withRankCheck(sender, target, () -> {
            CompletableFuture<Void> action_ = action.equals("grant")
                ? usersCommandApi.grantPermission(target.id(), node, finalExpiresAt)
                : usersCommandApi.revokePermission(target.id(), node);
            action_.thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
                String verb = action.equals("grant") ? "Granted" : "Revoked";
                String prep = action.equals("grant") ? " to " : " from ";
                sender.sendMessage(ChatColor.GREEN + verb + " '" + node + "'" + prep + target.username() + ".");
                refreshTargetVisibility(target);
            })).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex)));
                return null;
            });
        }));
    }

    /**
     * Resolves the sender's own account (Bukkit.getPlayer only, console senders are already
     * assumed to outrank everyone by convention elsewhere in /knk), checks RankHierarchy, and
     * only runs onAllowed if the actor's highest group weight exceeds the target's.
     */
    private void withRankCheck(CommandSender sender, UserSummary target, Runnable onAllowed) {
        if (!(sender instanceof Player senderPlayer)) {
            onAllowed.run(); // Console always allowed - matches /knk gate admin's console-safe precedent.
            return;
        }
        resolveTarget(sender, senderPlayer.getName(), actor ->
            rankHierarchy.actorOutranks(actor.id(), target.id()).thenAccept(outranks -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!outranks) {
                    sender.sendMessage(ChatColor.RED + "You cannot act on a player of equal or higher rank.");
                    return;
                }
                onAllowed.run();
            })).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed to check rank: " + describeError(ex)));
                return null;
            })
        );
    }

    /**
     * A group/perm change can affect what an online target may now see (e.g. granting
     * knk.mode.staff should let them immediately see already-vanished players, not just after
     * their next relog). Fixes the gap docs/specs/user-features/IMPLEMENTATION_PLAN.md §3's
     * "Gaps/bugs carried forward" item 3 flagged in advance: "Fine until §6.2's in-game grant
     * commands exist; those should call ModeService.refreshVisibilityFor(player) after changing
     * a player's grants." No-op if the target is offline or modeService wasn't wired.
     */
    private void refreshTargetVisibility(UserSummary target) {
        if (modeService == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer != null) {
            modeService.refreshVisibilityFor(targetPlayer);
        }
    }

    private void notifyGroupChange(UserSummary target, PermissionGroupSummary group, boolean added) {
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer == null) {
            return; // Offline - nothing to notify.
        }
        targetPlayer.playSound(targetPlayer.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        if (added) {
            targetPlayer.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ " + ChatColor.YELLOW + "You have been added to " + group.name() + "!");
        } else {
            targetPlayer.sendMessage(ChatColor.RED + "Your " + group.name() + " membership was removed.");
        }
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

    /**
     * Resolves targetName to a live UserSummary via the API directly (not FetchPolicy.CACHE_FIRST)
     * so an offline player's current balances are still accurate, and reports a clear "not found"
     * message on the calling sender rather than a raw 404. onFound always runs on the main thread.
     */
    private void resolveTarget(CommandSender sender, String targetName, Consumer<UserSummary> onFound) {
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
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> group add|remove <groupName> [duration]");
        sender.sendMessage(ChatColor.YELLOW + "  /knk user <player> perm grant|revoke <node> [duration]");
        sender.sendMessage(ChatColor.GRAY + "  duration examples: 2h, 90m, 3d (omit for permanent)");
    }
}
