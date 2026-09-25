package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /staffchat (/sc) &lt;message&gt; - rebuild of v1's StaffChatCommand. A one-shot broadcast per
 * invocation, not a toggleable channel (v1 never had a "stay in staff chat" mode - confirmed by
 * reading StaffChatCommand.java and its companion StaffChatEvents.java, whose one listener was
 * entirely commented out). Broadcasts to every online holder of knk.staffchat - the same node
 * that gates /msg's social-spy broadcast, so "who sees staffchat" and "who sees the spy feed" is
 * one audience.
 */
public class StaffChatCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /staffchat <message>");
            return true;
        }
        String message = String.join(" ", args);
        String senderName = sender instanceof Player ? sender.getName() : "CONSOLE";
        String formatted = ChatColor.LIGHT_PURPLE + "[Staff] " + ChatColor.WHITE + senderName + ChatColor.GRAY + ": " + ChatColor.WHITE + message;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("knk.staffchat")) {
                online.sendMessage(formatted);
                online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
            }
        }
        if (!(sender instanceof Player) || !sender.hasPermission("knk.staffchat")) {
            // Sender doesn't otherwise see their own broadcast (console, or an edge case where
            // the permission was revoked between the check and here) - echo it back.
            sender.sendMessage(formatted);
        }
        return true;
    }
}
