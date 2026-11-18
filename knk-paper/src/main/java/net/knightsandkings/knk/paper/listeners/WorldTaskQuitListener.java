package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.WorldTaskLeases;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * PandiO/knk-workspace#180 (DESIGN §8.2, §8.6 "player quits mid-task"): a player who leaves hands their world tasks
 * back. The local session (selection, temp region, WorldEdit session) is stopped and the task goes back to Pending with
 * its task code, so the web app can have it done by someone else, or by the same player after rejoining.
 */
public class WorldTaskQuitListener implements Listener {
    private final WorldTaskHandlerRegistry registry;
    private final WorldTaskLeases leases;

    public WorldTaskQuitListener(WorldTaskHandlerRegistry registry, WorldTaskLeases leases) {
        this.registry = registry;
        this.leases = leases;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        registry.cancelAllTasks(player);
        leases.releaseHeldBy(player.getUniqueId(), player.getName() + " left the game server");
    }
}
