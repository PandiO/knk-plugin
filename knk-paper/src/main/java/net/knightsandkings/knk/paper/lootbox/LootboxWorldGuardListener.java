package net.knightsandkings.knk.paper.lootbox;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent;

/**
 * Lets a player click a world box inside a protected region (KNG-266). WorldGuard refuses entity uses to anyone who isn't
 * a member of the region, so in a spawn area inside a domain region a non-op player's click on the box's hitbox was
 * cancelled before {@code LootboxInteractListener} saw it (smoke test 2026-10-22). For a lootbox entity only, a player's
 * WorldGuard use event is marked allowed before WorldGuard's region check (which runs at NORMAL and skips an allowed
 * event); every other entity in the region stays protected. The pickup's own checks (permission, staff mode, siege,
 * reach, inventory, the API) still decide. Same pattern as {@code HubWorldGuardListener}.
 * <p>
 * Only the right-click needs this: WorldGuard has no handler for Paper's {@code PrePlayerAttackEntityEvent} (the
 * left-click), and {@code LootboxTokenListener} reads token clicks whether or not they were cancelled.
 */
public final class LootboxWorldGuardListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(UseEntityEvent event) {
        if (event.getCause().getFirstPlayer() != null && LootboxPresenter.isBoxEntity(event.getEntity())) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
