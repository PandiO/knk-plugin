package net.knightsandkings.knk.paper.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

import net.knightsandkings.knk.paper.user.UserAdminService;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;

/**
 * /freeze &lt;player&gt; &lt;reason...&gt; and /unfreeze &lt;player&gt; - rebuild of v1's
 * FreezeCommands, which was a dead no-op stub (its onCommand body was literally "return false;")
 * despite a design-intent comment describing movement/chat/command/damage lockout. That lockout
 * is now actually enforced by AdminFreezeListener; this class just does the resolve + hierarchy
 * check + persist. Works on offline targets (resolveTarget hits the API directly), and does not
 * include v1's (also never built) 7-day quit-ban - no ban system exists in v3, developer-confirmed
 * out of scope for this round.
 */
public class FreezeCommand implements TabExecutor {
    private final UserAdminService userAdminService;
    private final boolean freezing;
    private final VisiblePlayers visiblePlayers;

    public FreezeCommand(UserAdminService userAdminService, boolean freezing) {
        this(userAdminService, freezing, VisiblePlayers.bukkit());
    }

    public FreezeCommand(UserAdminService userAdminService, boolean freezing, VisiblePlayers visiblePlayers) {
        this.userAdminService = userAdminService;
        this.freezing = freezing;
        this.visiblePlayers = visiblePlayers;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || (freezing && args.length < 2)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + (freezing ? "/freeze <player> <reason...>" : "/unfreeze <player>"));
            return true;
        }
        String targetName = args[0];
        String reason = freezing ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;

        // Rank check, API call, freeze-manager update and messages: UserAdminService.setFrozen,
        // shared with the in-game Player manager (InventoryMenu content port CP8).
        userAdminService.resolveTarget(sender, targetName, target -> userAdminService.setFrozen(sender, target, freezing, reason));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? visiblePlayers.completeOthers(sender, args[0]) : List.of();
    }
}
