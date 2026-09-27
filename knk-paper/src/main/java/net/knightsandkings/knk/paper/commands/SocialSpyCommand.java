package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.user.SpyService;

/**
 * /socialspy (/spy) [on|off] - turns your social spy feed on or off; no argument toggles it
 * (docs/specs/private-messages/DESIGN.md §3.3.4). Gated on {@code knk.socialspy} through
 * KnkPermissible. The choice is kept in the player's PDC, so it survives relogs and restarts.
 */
public class SocialSpyCommand implements TabExecutor {

    private final PlayerCommandSupport support;
    private final SpyService spyService;
    private final Logger logger;

    public SocialSpyCommand(PlayerCommandSupport support, SpyService spyService, Logger logger) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.spyService = Objects.requireNonNull(spyService, "spyService must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        Boolean requested = null;
        if (args.length > 0) {
            String arg = args[0].toLowerCase(Locale.ROOT);
            if (arg.equals("on")) {
                requested = true;
            } else if (arg.equals("off")) {
                requested = false;
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " [on|off]");
                return true;
            }
        }
        Boolean finalRequested = requested;
        support.whenAllowed(player, PrivateMessageNodes.SOCIAL_SPY, () -> {
            boolean enabled = finalRequested != null ? finalRequested : !spyService.isEnabled(player);
            spyService.setEnabled(player, enabled);
            // They just proved they hold the node - pick up any grant made since the last refresh.
            spyService.refresh(player);
            if (enabled) {
                player.sendMessage(ChatColor.GREEN + "Social spy enabled.");
            } else {
                player.sendMessage(ChatColor.GRAY + "Social spy disabled.");
            }
            logger.info(player.getName() + " turned social spy " + (enabled ? "on" : "off"));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
