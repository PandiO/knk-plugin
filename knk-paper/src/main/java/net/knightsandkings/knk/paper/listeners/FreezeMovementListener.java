package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.enchantment.FrozenPlayerTracker;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class FreezeMovementListener implements Listener {
    private final FrozenPlayerTracker frozenPlayerTracker;

    public FreezeMovementListener(FrozenPlayerTracker frozenPlayerTracker) {
        this.frozenPlayerTracker = frozenPlayerTracker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!frozenPlayerTracker.isFrozen(event.getPlayer().getUniqueId())) {
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
        if (samePosition) {
            return;
        }

        event.setTo(from);
    }

    /**
     * Death ends a freeze: a player frozen when they died could otherwise not move after respawning
     * until the freeze ran out, and a dead creature's entry would linger.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        frozenPlayerTracker.unfreeze(event.getEntity().getUniqueId());
    }

    /**
     * Leaving ends a freeze (PandiO/knk-workspace#155): the flight allowed for the freeze is handed back before the
     * player is saved, and a rejoin within the few seconds of a freeze doesn't find the player held without it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        frozenPlayerTracker.unfreeze(event.getPlayer().getUniqueId());
    }
}
