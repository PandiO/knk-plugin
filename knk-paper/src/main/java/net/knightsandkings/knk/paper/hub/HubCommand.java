package net.knightsandkings.knk.paper.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.commands.HubTeleportCommand;

/**
 * {@code /hub} (hub DESIGN §12):
 * <ul>
 *   <li>{@code /hub} and {@code /hub <player> [-s]} - to the hub spawn through the teleport engine (KNG-188,
 *       {@link HubTeleportCommand});</li>
 *   <li>{@code /hub return <token>} - the clickable return offer after an API outage. Players don't type it; the
 *       offer's button runs it with the token of their current return record. Without a token the offer is shown
 *       again.</li>
 * </ul>
 */
public final class HubCommand implements TabExecutor {

    private static final String RETURN = "return";

    private final HubOutageService outage;
    private final HubOutageService.Access access;
    private final HubOutageService.Blocker blocker;
    private volatile HubTeleportCommand teleport;

    public HubCommand(HubOutageService outage, HubOutageService.Access access, HubOutageService.Blocker blocker) {
        this.outage = Objects.requireNonNull(outage, "outage");
        this.access = Objects.requireNonNull(access, "access");
        this.blocker = Objects.requireNonNull(blocker, "blocker");
    }

    /** The {@code /hub [<player>]} teleport; null while the teleport engine isn't running. */
    public void setTeleport(HubTeleportCommand teleport) {
        this.teleport = teleport;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase(RETURN)) {
            HubTeleportCommand current = teleport;
            if (current == null) {
                sender.sendMessage(ChatColor.RED + "The hub isn't available right now.");
            } else {
                current.onCommand(sender, args);
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can return.");
            return true;
        }
        long token;
        try {
            token = args.length > 1 ? Long.parseLong(args[1]) : -1L;
        } catch (NumberFormatException e) {
            token = -1L;
        }
        if (token < 0) {
            outage.offerReturn(player);
            return true;
        }
        outage.returnTo(player, token, access, blocker);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0 || (args.length > 1 && args[0].equalsIgnoreCase(RETURN))) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && RETURN.startsWith(args[0].toLowerCase(Locale.ROOT))) {
            completions.add(RETURN);
        }
        HubTeleportCommand current = teleport;
        if (current != null) {
            completions.addAll(current.onTabComplete(sender, args));
        }
        return completions;
    }
}
