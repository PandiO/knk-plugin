package net.knightsandkings.knk.paper.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.user.MessagingService;

/** /reply (/r) &lt;message&gt; - replies to the last player who messaged (or was messaged by) you. */
public class ReplyCommand implements CommandExecutor {
    private final MessagingService messagingService;

    public ReplyCommand(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player senderPlayer)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /reply <message>");
            return true;
        }
        Player target = messagingService.replyTargetOf(senderPlayer);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "No one to reply to.");
            return true;
        }
        String message = String.join(" ", args);
        messagingService.send(sender, target, message);
        return true;
    }
}
