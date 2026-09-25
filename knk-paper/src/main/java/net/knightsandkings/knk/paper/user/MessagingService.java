package net.knightsandkings.knk.paper.user;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Player-to-player private messaging (rebuild of v1's MessageCommands: /message aka /msg, and
 * /reply aka /r) plus v1's always-on staff "social spy" broadcast (every DM is also echoed to
 * online holders of knk.staffchat, excluding sender/target - developer-confirmed to keep, unlike
 * everything else in this round which trims v1 behavior down). No permission gate on sending a
 * message itself, matching v1's player-to-player intent.
 * <p>
 * Reply-target tracking is a simple in-memory map (mirrors v1's msgReceived), reciprocal on send
 * (both parties can /reply to each other) and updated one-directionally on reply (matches v1).
 */
public class MessagingService {
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public void send(CommandSender sender, Player target, String message) {
        Player senderPlayer = sender instanceof Player p ? p : null;
        String senderName = sender.getName();

        sender.sendMessage(ChatColor.GRAY + "[me -> " + target.getName() + "] " + ChatColor.WHITE + message);
        target.sendMessage(ChatColor.GRAY + "[" + senderName + " -> me] " + ChatColor.WHITE + message);
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);

        if (senderPlayer != null) {
            lastPartner.put(senderPlayer.getUniqueId(), target.getUniqueId());
            lastPartner.put(target.getUniqueId(), senderPlayer.getUniqueId());
        }

        socialSpy(senderName, target.getName(), message, senderPlayer, target);
    }

    /** @return the player to reply to, or null if the sender has no prior message partner. */
    public Player replyTargetOf(Player sender) {
        UUID partnerUuid = lastPartner.get(sender.getUniqueId());
        if (partnerUuid == null) {
            return null;
        }
        return Bukkit.getPlayer(partnerUuid);
    }

    public void forget(UUID uuid) {
        lastPartner.remove(uuid);
    }

    private void socialSpy(String senderName, String targetName, String message, Player sender, Player target) {
        String spyLine = ChatColor.DARK_GRAY + "[Spy] " + senderName + " -> " + targetName + ": " + ChatColor.GRAY + message;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission("knk.staffchat")) {
                continue;
            }
            if (online.equals(sender) || online.equals(target)) {
                continue;
            }
            online.sendMessage(spyLine);
        }
    }
}
