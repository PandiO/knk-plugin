package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import net.knightsandkings.knk.paper.chat.PrivateMessageFormat;
import net.knightsandkings.knk.paper.user.MessagingService;

/**
 * /reply (/r) &lt;message&gt; - replies to the last player who messaged (or was messaged by) you.
 * The console can reply too, and players can reply to the console.
 */
public class ReplyCommand implements TabExecutor {

    /** Labels (primary + plugin.yml aliases) a frozen player may still use - see AdminFreezeListener. */
    public static final Set<String> LABELS = Set.of("reply", "r");

    private final MessagingService messagingService;

    public ReplyCommand(MessagingService messagingService) {
        this.messagingService = Objects.requireNonNull(messagingService, "messagingService must not be null");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String text = MessagingService.joinText(args, 0);
        if (text.isEmpty()) {
            sender.sendMessage(PrivateMessageFormat.usage("/" + label + " <message>"));
            return true;
        }
        messagingService.reply(sender, text);
        return true;
    }

    /** No completions: everything after /r is message text, not a player name. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
