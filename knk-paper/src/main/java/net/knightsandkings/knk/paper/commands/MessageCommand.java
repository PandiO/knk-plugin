package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.chat.PrivateMessageFormat;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.user.MessagingService;

/**
 * /msg (/message, /tell, /whisper, /w, /m, /pm) &lt;player&gt; &lt;message&gt; - see MessagingService.
 * The target is resolved vanish-safely: an unknown, offline or hidden name all get the same line.
 * The console may send too.
 */
public class MessageCommand implements TabExecutor {

    /** Labels (primary + plugin.yml aliases) a frozen player may still use - see AdminFreezeListener. */
    public static final Set<String> LABELS = Set.of("msg", "message", "tell", "whisper", "w", "m", "pm");

    private final MessagingService messagingService;
    private final VisiblePlayers visiblePlayers;

    public MessageCommand(MessagingService messagingService, VisiblePlayers visiblePlayers) {
        this.messagingService = Objects.requireNonNull(messagingService, "messagingService must not be null");
        this.visiblePlayers = Objects.requireNonNull(visiblePlayers, "visiblePlayers must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String text = MessagingService.joinText(args, 1);
        if (args.length < 2 || text.isEmpty()) {
            sender.sendMessage(PrivateMessageFormat.usage("/" + label + " <player> <message>"));
            return true;
        }
        Player target = visiblePlayers.require(sender, args[0]);
        if (target == null) {
            return true;
        }
        if (sender instanceof Player senderPlayer && senderPlayer.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't message yourself.");
            return true;
        }
        messagingService.send(sender, target, text, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return visiblePlayers.completeOthers(sender, args[0]);
        }
        return List.of();
    }
}
