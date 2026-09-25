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
import net.knightsandkings.knk.paper.user.AdminFreezeManager;

/**
 * Enforces an admin freeze (movement/chat/commands/damage locked) - rebuild of v1's
 * FreezeCommands' design-intent comment ("Shouldn't be able to walk, get damage, or run any
 * commands... shouldn't talk"), which was itself a dead no-op stub in v1 with none of this ever
 * actually built. No duel-teleport-block (no duel system exists in v3) and no quit-ban (no ban
 * system exists in v3, developer-confirmed out of scope for this round).
 */
public class AdminFreezeListener implements Listener {
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
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are frozen and cannot use commands.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
