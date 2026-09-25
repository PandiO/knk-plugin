package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.user.MessagingService;

/** /msg (/tell, /w) &lt;player&gt; &lt;message&gt; - see MessagingService. */
public class MessageCommand implements CommandExecutor {
    private final MessagingService messagingService;

    public MessageCommand(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /msg <player> <message>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No online player found named '" + args[0] + "'.");
            return true;
        }
        if (sender instanceof Player senderPlayer && senderPlayer.equals(target)) {
            sender.sendMessage(ChatColor.RED + "You can't message yourself.");
            return true;
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        messagingService.send(sender, target, message);
        return true;
    }
}
