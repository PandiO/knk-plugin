package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;

/**
 * /knk tp &lt;player&gt; - staff teleport-to-player (rebuild of the only real, working part of
 * v1's PlayerTeleportCommand: the owner-mode tier's instant self-teleport-to-target; the
 * normal-player paid-TPA-request flow and the two-player-teleport variant were both broken/
 * non-functional in v1, per docs/specs/legacy/commands-v1.md, so nothing there to port).
 * Deliberately under /knk rather than a bare /tp, to avoid colliding with vanilla teleport
 * semantics. Hierarchy-checked (RankHierarchy), console-exempt (matches /knk gate admin's
 * console-safe precedent for non-hierarchy checks, though teleport itself requires a player
 * sender since console has no location).
 */
public class TeleportToPlayerCommand {
    private final org.bukkit.plugin.Plugin plugin;
    private final UsersDataAccess usersDataAccess;
    private final RankHierarchy rankHierarchy;

    public TeleportToPlayerCommand(org.bukkit.plugin.Plugin plugin, UsersDataAccess usersDataAccess, RankHierarchy rankHierarchy) {
        this.plugin = plugin;
        this.usersDataAccess = usersDataAccess;
        this.rankHierarchy = rankHierarchy;
    }

    public boolean onCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player senderPlayer)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk tp <player>");
            return true;
        }
        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "No online player found named '" + targetName + "'.");
            return true;
        }

        resolveTarget(sender, senderPlayer.getName(), actor ->
            resolveTarget(sender, targetName, target ->
                rankHierarchy.actorOutranks(actor.id(), target.id()).thenAccept(outranks -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!outranks) {
                        sender.sendMessage(ChatColor.RED + "You cannot act on a player of equal or higher rank.");
                        return;
                    }
                    Player liveTarget = Bukkit.getPlayerExact(targetName);
                    if (liveTarget == null) {
                        sender.sendMessage(ChatColor.RED + targetName + " went offline.");
                        return;
                    }
                    senderPlayer.teleport(liveTarget.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Teleported to " + liveTarget.getName() + ".");
                })).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Failed to check rank: " + describeError(ex)));
                    return null;
                })
            )
        );
        return true;
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
