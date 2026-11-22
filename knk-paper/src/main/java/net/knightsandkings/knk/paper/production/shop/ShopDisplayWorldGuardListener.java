package net.knightsandkings.knk.paper.production.shop;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent;

/**
 * Lets a player click a display stall inside a protected region (PandiO/knk-workspace#250; the lootbox pattern, KNG-266).
 * WorldGuard refuses entity uses to anyone who isn't a member of the region, and shops stand in town regions; for a stall
 * entity only, a player's WorldGuard use event is marked allowed before WorldGuard's region check (NORMAL). Every other
 * entity stays protected; the shop's own checks (siege, stock, the API) still decide. Registered only with WorldGuard.
 */
public final class ShopDisplayWorldGuardListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(UseEntityEvent event) {
        if (event.getCause().getFirstPlayer() != null && ShopDisplayPresenter.isStallEntity(event.getEntity())) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
