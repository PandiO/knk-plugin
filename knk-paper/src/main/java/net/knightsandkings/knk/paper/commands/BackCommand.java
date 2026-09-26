package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.teleport.BackService;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;

/**
 * {@code /back} (docs/specs/teleport/IMPLEMENTATION_PLAN.md Phase 7; developer decision Q5) - back to
 * where you last died, within {@code teleport.back.expire-seconds} (5 min), once per death, not after
 * a siege death. Needs {@value TeleportNodes#BACK} (Dragon Blood). A player teleport: warmup,
 * cooldown, combat tag, safe-spot check and every guard apply ({@link BackService}). Players only.
 * Permissions resolve through {@code KnkPermissible}, hence no {@code permission:} in plugin.yml.
 */
public class BackCommand implements TabExecutor {

    private final PlayerCommandSupport support;
    private final BackService backService;

    public BackCommand(PlayerCommandSupport support, BackService backService) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.backService = Objects.requireNonNull(backService, "backService must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length > 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /back");
            return true;
        }
        if (!backService.isEnabled()) {
            sender.sendMessage(ChatColor.RED + "/back is turned off on this server.");
            return true;
        }
        support.whenAllowed(sender, TeleportNodes.BACK, () ->
            backService.start(player).thenAccept(outcome -> report(player, outcome)));
        return true;
    }

    /** Tell the player how their {@code /back} ended; the engine already explained a cancelled warmup. */
    static void report(Player player, TeleportOutcome outcome) {
        if (!player.isOnline()) {
            return;
        }
        switch (outcome.status()) {
            case TELEPORTED -> player.sendMessage(ChatColor.GREEN + "Teleported back to where you died.");
            case CANCELLED -> { }
            case DENIED, FAILED -> {
                if (TeleportDenial.UNSAFE.equals(outcome.code())) {
                    player.sendMessage(ChatColor.RED + "Where you died isn't safe to return to (no safe ground nearby).");
                } else {
                    player.sendMessage(ChatColor.RED
                        + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
