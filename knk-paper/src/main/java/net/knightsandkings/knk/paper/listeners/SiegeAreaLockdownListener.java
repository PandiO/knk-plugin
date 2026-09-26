package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.siege.SiegeAreaLockdown;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Siege Phase 7a (DESIGN §8.5): non-members can't walk or teleport into a scenario area that is
 * locked down for a running round. The rules live in {@link SiegeAreaLockdown}; this only cancels.
 */
public final class SiegeAreaLockdownListener implements Listener {

    private final SiegeAreaLockdown lockdown;

    public SiegeAreaLockdownListener(SiegeAreaLockdown lockdown) {
        this.lockdown = lockdown;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!lockdown.isActive()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) return;
        lockdown.blockingEntry(event.getPlayer(), from, to).ifPresent(area -> {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(SiegeMessages.bad("The siege " + area.runtime().displayName()
                    + " is on; this area is closed until it ends."));
        });
    }

    /**
     * Player travel (commands, ender pearls, chorus fruit, portals, spectating). Plugin teleports are
     * let through: the siege's own vault restore teleports a player who just stopped being a member
     * back to where they were, which may be inside the area.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!lockdown.isActive() || event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        lockdown.blockingEntry(event.getPlayer(), event.getFrom(), event.getTo()).ifPresent(area -> {
            event.setCancelled(true);
            event.getPlayer().sendMessage(SiegeMessages.bad("You can't teleport there: the siege "
                    + area.runtime().displayName() + " is on and the area is closed until it ends."));
        });
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld() && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
