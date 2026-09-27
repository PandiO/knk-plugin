package net.knightsandkings.knk.paper.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.paper.commands.MessageCommand;
import net.knightsandkings.knk.paper.commands.ReplyCommand;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;

/**
 * Enforces an admin freeze (movement/chat/commands/damage locked; /msg and /r stay open so the
 * player can answer staff - docs/specs/private-messages/DESIGN.md §3.3.9) - rebuild of v1's
 * FreezeCommands' design-intent comment ("Shouldn't be able to walk, get damage, or run any
 * commands... shouldn't talk"), which was itself a dead no-op stub in v1 with none of this ever
 * actually built. No duel-teleport-block (no duel system exists in v3) and no quit-ban (no ban
 * system exists in v3, developer-confirmed out of scope for this round).
 */
public class AdminFreezeListener implements Listener {
    private static final String PLUGIN_NAMESPACE = "knightsandkings:";

    private final Plugin plugin;
    private final AdminFreezeManager freezeManager;
    private final UsersDataAccess usersDataAccess;

    public AdminFreezeListener(Plugin plugin, AdminFreezeManager freezeManager, UsersDataAccess usersDataAccess) {
        this.plugin = plugin;
        this.freezeManager = freezeManager;
        this.usersDataAccess = usersDataAccess;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        freezeManager.restoreOnJoin(plugin, event.getPlayer(), usersDataAccess);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        freezeManager.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!freezeManager.isFrozen(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        boolean samePosition = from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ();
        if (!samePosition) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are frozen and cannot chat.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (freezeManager.isFrozen(player.getUniqueId())) {
            if (isPrivateMessageCommand(event.getMessage())) {
                // A frozen player may answer staff: /msg and /r pass here, and MessagingService's
                // FrozenGate only lets them reach knk.freeze holders (v1's design intent).
                return;
            }
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are frozen and cannot use commands. You can still /msg staff.");
        }
    }

    /** /msg, /reply and their aliases, also in this plugin's namespaced form (knightsandkings:msg). */
    static boolean isPrivateMessageCommand(String message) {
        String label = VanillaMessagingBlockListener.label(message);
        if (label.startsWith(PLUGIN_NAMESPACE)) {
            label = label.substring(PLUGIN_NAMESPACE.length());
        }
        return MessageCommand.LABELS.contains(label) || ReplyCommand.LABELS.contains(label);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
