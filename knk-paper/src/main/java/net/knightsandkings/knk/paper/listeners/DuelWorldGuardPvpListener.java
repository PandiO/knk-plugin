package net.knightsandkings.knk.paper.listeners;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import net.knightsandkings.knk.paper.duel.DuelService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * WorldGuard's own PvP block for the fighting pair (arena duels DESIGN §8.1): WorldGuard fires
 * {@link DisallowedPVPEvent} when a region's {@code pvp} flag would stop a hit, including the paths
 * {@link DuelCombatListener} never sees as a plain hit (splash and lingering potions, fire, ...). Cancelling the event
 * lets the hit through, for two duelists of one match in FIGHT only ({@link DuelService#liftsWorldGuardPvpBlock}).
 * Present in WorldGuard 7.0.20, the build on the KNG-182 branch.
 */
public class DuelWorldGuardPvpListener implements Listener {

    private final DuelService service;

    public DuelWorldGuardPvpListener(DuelService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisallowedPvp(DisallowedPVPEvent event) {
        if (event.getAttacker() == null || event.getDefender() == null) return;
        if (service.liftsWorldGuardPvpBlock(event.getAttacker().getUniqueId(), event.getDefender().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
