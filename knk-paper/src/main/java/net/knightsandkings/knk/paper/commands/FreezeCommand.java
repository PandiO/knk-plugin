package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;

/**
 * /freeze &lt;player&gt; &lt;reason...&gt; and /unfreeze &lt;player&gt; - rebuild of v1's
 * FreezeCommands, which was a dead no-op stub (its onCommand body was literally "return false;")
 * despite a design-intent comment describing movement/chat/command/damage lockout. That lockout
 * is now actually enforced by AdminFreezeListener; this class just does the resolve + hierarchy
 * check + persist. Works on offline targets (resolveTarget hits the API directly), and does not
 * include v1's (also never built) 7-day quit-ban - no ban system exists in v3, developer-confirmed
 * out of scope for this round.
 */
public class FreezeCommand implements CommandExecutor {
    private final Plugin plugin;
    private final UsersDataAccess usersDataAccess;
    private final UsersCommandApi usersCommandApi;
    private final RankHierarchy rankHierarchy;
    private final AdminFreezeManager freezeManager;
    private final boolean freezing;

    public FreezeCommand(Plugin plugin, UsersDataAccess usersDataAccess, UsersCommandApi usersCommandApi,
                          RankHierarchy rankHierarchy, AdminFreezeManager freezeManager, boolean freezing) {
        this.plugin = plugin;
        this.usersDataAccess = usersDataAccess;
        this.usersCommandApi = usersCommandApi;
        this.rankHierarchy = rankHierarchy;
        this.freezeManager = freezeManager;
        this.freezing = freezing;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || (freezing && args.length < 2)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + (freezing ? "/freeze <player> <reason...>" : "/unfreeze <player>"));
            return true;
        }
        String targetName = args[0];
        String reason = freezing ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;

        resolveTarget(sender, targetName, target -> withRankCheck(sender, target, () -> {
            var action = freezing
                ? usersCommandApi.freezeById(target.id(), reason)
                : usersCommandApi.unfreezeById(target.id());
            action.thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
                Player targetPlayer = Bukkit.getPlayerExact(target.username());
                if (freezing) {
                    if (targetPlayer != null) {
                        freezeManager.freeze(targetPlayer.getUniqueId(), reason);
                        targetPlayer.sendMessage(ChatColor.RED + "You have been frozen: " + reason);
                    }
                    sender.sendMessage(ChatColor.GREEN + "Froze " + target.username() + (targetPlayer == null ? " (offline - takes effect on next join)." : "."));
                } else {
                    if (targetPlayer != null) {
                        freezeManager.unfreeze(targetPlayer.getUniqueId());
                        targetPlayer.sendMessage(ChatColor.GREEN + "You have been unfrozen.");
                    }
                    sender.sendMessage(ChatColor.GREEN + "Unfroze " + target.username() + ".");
                }
            })).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex)));
                return null;
            });
        }));
        return true;
    }

    private void withRankCheck(CommandSender sender, UserSummary target, Runnable onAllowed) {
        if (!(sender instanceof Player senderPlayer)) {
            onAllowed.run();
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
}
