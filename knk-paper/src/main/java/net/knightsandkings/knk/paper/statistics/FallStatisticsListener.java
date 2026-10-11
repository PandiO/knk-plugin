package net.knightsandkings.knk.paper.statistics;

import java.util.OptionalDouble;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import net.knightsandkings.knk.core.statistics.FallRule;

/**
 * Highest survived fall (DESIGN.md §F.9, D3): fall damage observed at MONITOR is recorded when the
 * player survives it. {@link EntityDamageEvent#getFinalDamage()} already has the absorption hearts
 * taken off, so the remaining health is compared with the health alone.
 */
public final class FallStatisticsListener implements Listener {

    private final StatisticsService service;

    public FallStatisticsListener(StatisticsService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!service.hasSession(player.getUniqueId()) || service.excluded(player)) {
            return;
        }
        OptionalDouble fall = FallRule.survivedFall(player.getFallDistance(), player.getHealth(), event.getFinalDamage(), 0);
        fall.ifPresent(blocks -> service.recordFall(player, blocks));
    }
}
