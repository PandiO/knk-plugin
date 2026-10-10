package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.enchantment.FrozenPlayerTracker;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;

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
}
