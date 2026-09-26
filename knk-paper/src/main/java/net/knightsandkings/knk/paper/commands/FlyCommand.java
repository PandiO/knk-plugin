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

/**
 * {@code /fly [on|off] [player]} - port of v2's {@code Fly} (docs/specs/legacy/commands-v2.md §7, KNG-9).
 * <ul>
 *   <li>{@code /fly} toggles your own flight ({@value #NODE}).</li>
 *   <li>{@code /fly <player>} toggles an online player's flight ({@value #NODE_OTHERS}).</li>
 *   <li>{@code /fly <on|off> [player]} sets it instead of toggling (v2's {@code <true|false> <user>};
 *       {@code true}/{@code false} still work).</li>
 * </ul>
 * Permissions resolve through {@code KnkPermissible} (the REST-backed permission model), not Bukkit's
 * permission tree - hence no {@code permission:} on the plugin.yml command entry.
 */
public class FlyCommand implements TabExecutor {

    public static final String NODE = "knk.fly";
    public static final String NODE_OTHERS = "knk.fly.others";

    private static final List<String> STATES = List.of("on", "off");

    private final PlayerCommandSupport support;

    public FlyCommand(PlayerCommandSupport support) {
        this.support = support;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 2) {
            sendUsage(sender);
            return true;
        }

        Boolean state = args.length > 0 ? parseState(args[0]) : null;
        String targetName;
        if (state != null) {
            targetName = args.length == 2 ? args[1] : null;
        } else if (args.length == 2) {
            sendUsage(sender); // two args, but the first isn't on/off
            return true;
        } else {
            targetName = args.length == 1 ? args[0] : null;
        }

        Player target;
        if (targetName == null) {
            target = support.requirePlayer(sender);
            if (target == null) {
                sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /fly [on|off] <player>");
                return true;
            }
        } else {
            target = support.requireOnlinePlayer(sender, targetName, null);
            if (target == null) {
                return true;
            }
        }

        boolean self = PlayerCommandSupport.isSelf(sender, target);
        support.whenAllowed(sender, self ? NODE : NODE_OTHERS, () -> apply(sender, target, self, state));
        return true;
    }

    private void apply(CommandSender sender, Player target, boolean self, Boolean requested) {
        if (!target.isOnline()) {
            sender.sendMessage(ChatColor.RED + target.getName() + " went offline.");
            return;
        }
        boolean enable = requested != null ? requested : !target.getAllowFlight();
        target.setAllowFlight(enable);
        if (!enable) {
            target.setFlying(false);
        }

        String status = enable ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled";
        if (self) {
            target.sendMessage(ChatColor.GRAY + "Flight " + status + ChatColor.GRAY + ".");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Flight " + status + ChatColor.GRAY + " for " + ChatColor.WHITE + target.getName() + ChatColor.GRAY + ".");
        target.sendMessage(ChatColor.GRAY + "Your flight was " + status + ChatColor.GRAY + " by " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ".");
    }

    /** on/true/enable → true, off/false/disable → false, anything else → null (a player name). */
    static Boolean parseState(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> Boolean.TRUE;
            case "off", "false", "disable" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /fly [on|off] [player]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return support.completePlayers(args[0], STATES.toArray(String[]::new));
        }
        if (args.length == 2 && parseState(args[0]) != null) {
            return support.completePlayers(args[1]);
        }
        return Collections.emptyList();
    }
}
